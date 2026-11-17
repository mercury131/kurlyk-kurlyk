// Package proxy implements proxy mode.
//
// Proxy mode (PROXY=true): this instance is a transparent relay in front of another securegate
// server (DOMAIN_TO_PROXY). Everything is forwarded at TCP/UDP level, TLS is NOT terminated
// here, so SSTP (incl. crypto binding), MTProxy FakeTLS, the chat (HTTPS + WebSocket),
// TURN-over-TLS and the website work exactly as on the main server:
//
//	:443/tcp -> DOMAIN_TO_PROXY:443  (PROXY v2 header with the real client address)
//	:80/tcp  -> DOMAIN_TO_PROXY:80   (Let's Encrypt HTTP-01 for this proxy's domain + redirect)
//	:TURN_UDP_PORT/udp -> DOMAIN_TO_PROXY:TURN_UDP_PORT (STUN/TURN for calls)
//
// The main server lists this proxy's domain in PROXY_FRONTENDS: it then trusts the PROXY header
// from the proxy's IP, accepts the domain as SNI/Host and obtains a Let's Encrypt certificate for
// it (the ACME challenge arrives at the proxy and is relayed to the main server).

package proxy

import (
	"context"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"sync"
	"syscall"
	"time"

	"securegate/internal/config"
	"securegate/internal/guard"
	"securegate/internal/mtproxy"
	"securegate/internal/netx"
)

// Run runs the relay until SIGINT/SIGTERM.
func Run(cfg *config.Config) {
	upHost, upPort, err := net.SplitHostPort(cfg.ProxyUpstream)
	if err != nil {
		upHost, upPort = cfg.ProxyUpstream, "443"
	}
	slog.Info("securegate starting in PROXY mode (SSTP/MTProxy/chat/TURN/site settings are ignored)",
		"domain", cfg.Domain, "upstream", cfg.ProxyUpstream, "udp", cfg.ProxyUDP)
	_ = os.MkdirAll(cfg.DataDir, 0o700)
	// the telemt container is not needed on a proxy: let it idle
	_ = mtproxy.WriteDisabled(cfg.MTConfigDir, "proxy mode (PROXY=true): MTProxy runs on the main server "+cfg.ProxyUpstream)

	g := guard.New(guard.Config{
		MaxConnPerIP: cfg.GuardMaxConnPerIP,
		RatePerMin:   cfg.GuardRatePerMin,
		Strikes:      cfg.GuardStrikes,
		BanBase:      cfg.GuardBan,
		Whitelist:    cfg.GuardWhitelist,
		NFT:          cfg.GuardNFT,
		NFTPorts:     []int{80, cfg.HTTPSPort, cfg.TURNUDPPort},
		StateFile:    filepath.Join(cfg.DataDir, "guard", "bans.json"),
	})
	defer g.Close()

	r443 := &tcpRelay{name: "https", guard: g, upstream: net.JoinHostPort(upHost, upPort)}
	r80 := &tcpRelay{name: "http", guard: g, upstream: net.JoinHostPort(upHost, "80")}
	ln443, err := net.Listen("tcp", cfg.HTTPSAddr)
	if err != nil {
		slog.Error("listen", "addr", cfg.HTTPSAddr, "err", err)
		os.Exit(1)
	}
	ln80, err := net.Listen("tcp", cfg.HTTPAddr)
	if err != nil {
		slog.Error("listen", "addr", cfg.HTTPAddr, "err", err)
		os.Exit(1)
	}
	go r443.serve(ln443)
	go r80.serve(ln80)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if cfg.ProxyUDP {
		u := &udpRelay{guard: g, upstream: net.JoinHostPort(upHost, strconv.Itoa(cfg.TURNUDPPort))}
		pc, err := net.ListenUDP("udp", &net.UDPAddr{Port: cfg.TURNUDPPort})
		if err != nil {
			slog.Error("proxy: UDP listen failed, TURN UDP not relayed", "port", cfg.TURNUDPPort, "err", err)
		} else {
			go u.serve(ctx, pc)
			defer pc.Close()
		}
	}

	go func() { // connectivity check of the upstream, so misconfiguration is visible in the log
		for {
			c, err := net.DialTimeout("tcp", r443.upstream, 10*time.Second)
			if err != nil {
				slog.Error("proxy: main server is NOT reachable", "upstream", r443.upstream, "err", err)
			} else {
				_, _ = c.Write(netx.ProxyV2Local())
				c.Close()
				slog.Info("proxy: main server reachable", "upstream", r443.upstream, "addr", c.RemoteAddr())
				return
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(30 * time.Second):
			}
		}
	}()

	_ = os.WriteFile("/tmp/securegate.ready", []byte("ok"), 0o644)
	slog.Info("securegate proxy ready", "https", cfg.HTTPSAddr, "http", cfg.HTTPAddr,
		"hint", "on the MAIN server add this proxy's domain to PROXY_FRONTENDS")

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
	for s := range sig {
		if s == syscall.SIGHUP {
			g.Reload()
			continue
		}
		break
	}
	slog.Info("shutting down")
	ln443.Close()
	ln80.Close()
}

type tcpRelay struct {
	name     string
	guard    *guard.Guard
	upstream string
	mu       sync.Mutex
	lastErr  time.Time
}

func (r *tcpRelay) serve(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			if netx.IsTimeout(err) {
				time.Sleep(50 * time.Millisecond)
				continue
			}
			return
		}
		go r.handle(c)
	}
}

func (r *tcpRelay) handle(c net.Conn) {
	ip := guard.IPOf(c.RemoteAddr())
	release, ok := r.guard.Admit(ip)
	if !ok {
		netx.Reset(c)
		return
	}
	defer release()
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(30 * time.Second)
	}
	// Do not open upstream connections for silent port scans: wait for the first bytes.
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	buf := make([]byte, 16384)
	n, err := c.Read(buf)
	if n == 0 {
		if err != nil {
			r.guard.Strike(ip, 1, "connect without data")
		}
		netx.Reset(c)
		return
	}
	_ = c.SetReadDeadline(time.Time{})
	up, err := net.DialTimeout("tcp", r.upstream, 10*time.Second)
	if err != nil {
		r.mu.Lock()
		logIt := time.Since(r.lastErr) > 30*time.Second
		if logIt {
			r.lastErr = time.Now()
		}
		r.mu.Unlock()
		if logIt {
			slog.Error("proxy: cannot connect to main server", "port", r.name, "upstream", r.upstream, "err", err)
		}
		netx.Reset(c)
		return
	}
	if tc, ok := up.(*net.TCPConn); ok {
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(30 * time.Second)
	}
	hdr := netx.ProxyV2Header(c.RemoteAddr(), c.LocalAddr())
	if _, err := up.Write(append(hdr, buf[:n]...)); err != nil {
		up.Close()
		netx.Reset(c)
		return
	}
	slog.Debug("proxy: relaying", "port", r.name, "ip", ip)
	netx.Pipe(c, up)
}

// udpRelay forwards STUN/TURN datagrams: one upstream socket per client address.
// The main server's TURN sees the proxy as the client (TURN authenticates by credentials).
type udpRelay struct {
	guard    *guard.Guard
	upstream string
	mu       sync.Mutex
	sessions map[string]*udpSession
}

type udpSession struct {
	up   *net.UDPConn // unconnected: the main server may answer from another of its addresses
	dst  *net.UDPAddr
	last time.Time
	mu   sync.Mutex
}

const (
	udpIdle        = 10 * time.Minute
	udpMaxSessions = 2000
)

func (u *udpRelay) serve(ctx context.Context, pc *net.UDPConn) {
	u.sessions = map[string]*udpSession{}
	go func() {
		t := time.NewTicker(time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				u.mu.Lock()
				for k, s := range u.sessions {
					s.mu.Lock()
					idle := time.Since(s.last) > udpIdle
					s.mu.Unlock()
					if idle {
						s.up.Close()
						delete(u.sessions, k)
					}
				}
				u.mu.Unlock()
			}
		}
	}()
	buf := make([]byte, 65535)
	for {
		n, from, err := pc.ReadFromUDP(buf)
		if err != nil {
			if netx.IsTimeout(err) {
				continue
			}
			return
		}
		key := from.String()
		u.mu.Lock()
		s := u.sessions[key]
		if s == nil {
			if len(u.sessions) >= udpMaxSessions || u.guard.Banned(from.IP.String()) {
				u.mu.Unlock()
				continue
			}
			raddr, err := net.ResolveUDPAddr("udp", u.upstream)
			if err != nil {
				u.mu.Unlock()
				slog.Error("proxy: cannot resolve main server (udp)", "upstream", u.upstream, "err", err)
				continue
			}
			up, err := net.ListenUDP("udp", nil)
			if err != nil {
				u.mu.Unlock()
				continue
			}
			s = &udpSession{up: up, dst: raddr}
			u.sessions[key] = s
			go u.back(pc, s, from)
		}
		u.mu.Unlock()
		s.mu.Lock()
		s.last = time.Now()
		s.mu.Unlock()
		_, _ = s.up.WriteToUDP(buf[:n], s.dst)
	}
}

func (u *udpRelay) back(pc *net.UDPConn, s *udpSession, client *net.UDPAddr) {
	buf := make([]byte, 8192) // STUN/TURN datagrams are < MTU; keeps memory low with many sessions
	for {
		n, from, err := s.up.ReadFromUDP(buf)
		if err != nil {
			return // closed by the idle janitor
		}
		if from.Port != s.dst.Port { // only answers of the main server's STUN/TURN port
			continue
		}
		s.mu.Lock()
		s.last = time.Now()
		s.mu.Unlock()
		_, _ = pc.WriteToUDP(buf[:n], client)
	}
}
