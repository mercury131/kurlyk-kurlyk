// Package mux is the single :443 entry point. It classifies every incoming connection:
//
//	TLS ClientHello with MTProxy FakeTLS HMAC  -> telemt (raw, PROXY v2)
//	other TLS -> terminate TLS -> "SSTP_DUPLEX_POST" -> SSTP server
//	                           -> STUN/TURN header   -> TURN server
//	                           -> anything else      -> HTTP (site + chat)
//	non-TLS                                         -> strike + reset
package mux

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"io"
	"log/slog"
	"net"
	"time"

	"golang.org/x/crypto/acme"

	"securegate/internal/frontend"
	"securegate/internal/guard"
	"securegate/internal/mtproxy"
	"securegate/internal/netx"
	"securegate/internal/sstp"
	"securegate/internal/turnsrv"
)

type Server struct {
	Serves    func(host string) bool // DOMAIN or a proxy frontend domain
	Frontends *frontend.Set          // proxy instances (PROXY v2 header with the real client IP)
	Guard     *guard.Guard
	TLS       *tls.Config
	MT        *mtproxy.Forwarder // nil if disabled
	SSTP      *sstp.Server       // nil if disabled
	TURN      *turnsrv.Server    // nil if disabled
	HTTP      *netx.ChanListener
}

// Serve accepts connections on ln until it is closed.
func (s *Server) Serve(ln net.Listener) error {
	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				time.Sleep(50 * time.Millisecond)
				continue
			}
			slog.Error("mux: accept", "err", err)
			time.Sleep(200 * time.Millisecond)
			continue
		}
		go s.handle(c)
	}
}

func (s *Server) handle(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(30 * time.Second)
	}
	if s.Frontends.TrustedAddr(c.RemoteAddr()) {
		c2, ok := AcceptProxied(c)
		if !ok {
			return
		}
		c = c2
	}
	ip := guard.IPOf(c.RemoteAddr())
	release, ok := s.Guard.Admit(ip)
	if !ok {
		netx.Reset(c)
		return
	}
	handedOff := false
	defer func() {
		if !handedOff {
			release()
		}
	}()

	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	hdr := make([]byte, 5)
	n, err := io.ReadFull(c, hdr)
	if err != nil {
		if n == 0 {
			s.Guard.Strike(ip, 1, "connect without data")
		} else {
			s.Guard.Strike(ip, 2, "short/non-TLS data")
		}
		netx.Reset(c)
		return
	}
	if hdr[0] != 0x16 || hdr[1] != 0x03 {
		slog.Debug("mux: non-TLS traffic", "ip", ip, "head", hdr)
		s.Guard.Strike(ip, 3, "non-TLS traffic on 443")
		netx.Reset(c)
		return
	}
	recLen := int(binary.BigEndian.Uint16(hdr[3:]))
	if recLen < 40 || recLen > 16384 {
		s.Guard.Strike(ip, 3, "invalid TLS record")
		netx.Reset(c)
		return
	}
	rec := make([]byte, 5+recLen)
	copy(rec, hdr)
	if _, err := io.ReadFull(c, rec[5:]); err != nil {
		s.Guard.Strike(ip, 2, "truncated ClientHello")
		netx.Reset(c)
		return
	}

	// 1. MTProxy FakeTLS?
	if s.MT != nil {
		if user, ok := s.MT.Match(rec); ok {
			_ = c.SetReadDeadline(time.Time{})
			slog.Info("mtproxy: client", "ip", ip, "user", user, "sni", parseSNI(rec[5:]))
			s.MT.Forward(c, rec)
			return
		}
	}

	// 2. Our TLS.
	sni := parseSNI(rec[5:])
	if sni != "" && !s.Serves(sni) {
		s.Guard.Strike(ip, 2, "foreign SNI "+truncate(sni, 60))
		netx.Reset(c)
		return
	}
	rc := &netx.ReaderConn{Conn: c, R: io.MultiReader(bytes.NewReader(rec), c)}
	tconn := tls.Server(rc, s.TLS)
	_ = tconn.SetDeadline(time.Now().Add(15 * time.Second))
	if err := tconn.Handshake(); err != nil {
		slog.Debug("mux: TLS handshake failed", "ip", ip, "err", err)
		s.Guard.Strike(ip, 1, "TLS handshake failed")
		netx.Reset(c)
		return
	}
	if tconn.ConnectionState().NegotiatedProtocol == acme.ALPNProto {
		tconn.Close()
		return
	}
	_ = tconn.SetDeadline(time.Time{})
	_ = tconn.SetReadDeadline(time.Now().Add(30 * time.Second))
	br := bufio.NewReaderSize(tconn, 16384)
	head, err := br.Peek(4)
	if err != nil {
		tconn.Close()
		return
	}
	_ = tconn.SetReadDeadline(time.Time{})

	switch {
	case string(head) == "SSTP":
		if s.SSTP == nil {
			s.Guard.Strike(ip, 1, "sstp disabled")
			tconn.Close()
			return
		}
		s.SSTP.Handle(&netx.ReaderConn{Conn: tconn, R: br}, br, ip)
	case head[0] < 0x40 && s.TURN != nil:
		if h8, err := br.Peek(8); err == nil && binary.BigEndian.Uint32(h8[4:]) == 0x2112A442 {
			handedOff = true
			s.TURN.PushTCP(&netx.ReaderConn{Conn: tconn, R: br, OnClose: release})
			return
		}
		s.Guard.Strike(ip, 2, "unknown binary protocol inside TLS")
		tconn.Close()
	default:
		handedOff = true
		s.HTTP.Push(&netx.ReaderConn{Conn: tconn, R: br, OnClose: release})
	}
}

// AcceptProxied handles a connection from a trusted proxy instance: with a PROXY v2 header it
// returns the connection with the real client address; without one it is a direct client that
// shares the proxy's public IP and is returned as is. On error the connection is closed.
func AcceptProxied(c net.Conn) (net.Conn, bool) {
	c2, err := netx.MaybeProxied(c, 10*time.Second)
	if err != nil {
		if errors.Is(err, netx.ErrProxyLocal) { // connectivity check of the proxy itself
			_ = c.Close()
		} else {
			slog.Debug("frontend: bad connection from proxy address", "from", c.RemoteAddr(), "err", err)
			netx.Reset(c)
		}
		return nil, false
	}
	return c2, true
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

// parseSNI extracts server_name from a TLS handshake message (ClientHello) body.
func parseSNI(b []byte) string {
	// handshake header: type(1)=1, len(3)
	if len(b) < 4 || b[0] != 1 {
		return ""
	}
	b = b[4:]
	if len(b) < 34 {
		return ""
	}
	b = b[34:] // version + random
	if len(b) < 1 {
		return ""
	}
	l := int(b[0])
	if len(b) < 1+l {
		return ""
	}
	b = b[1+l:] // session id
	if len(b) < 2 {
		return ""
	}
	l = int(binary.BigEndian.Uint16(b))
	if len(b) < 2+l {
		return ""
	}
	b = b[2+l:] // cipher suites
	if len(b) < 1 {
		return ""
	}
	l = int(b[0])
	if len(b) < 1+l {
		return ""
	}
	b = b[1+l:] // compression
	if len(b) < 2 {
		return ""
	}
	l = int(binary.BigEndian.Uint16(b))
	b = b[2:]
	if len(b) < l {
		return ""
	}
	b = b[:l]
	for len(b) >= 4 {
		typ := binary.BigEndian.Uint16(b)
		el := int(binary.BigEndian.Uint16(b[2:]))
		b = b[4:]
		if len(b) < el {
			return ""
		}
		if typ == 0 { // server_name
			d := b[:el]
			if len(d) < 2 {
				return ""
			}
			d = d[2:]
			for len(d) >= 3 {
				nt := d[0]
				nl := int(binary.BigEndian.Uint16(d[1:]))
				d = d[3:]
				if len(d) < nl {
					return ""
				}
				if nt == 0 {
					return string(d[:nl])
				}
				d = d[nl:]
			}
			return ""
		}
		b = b[el:]
	}
	return ""
}
