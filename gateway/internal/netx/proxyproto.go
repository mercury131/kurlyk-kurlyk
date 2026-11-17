package netx

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"time"
)

// PROXY protocol v2 (haproxy spec). Used between a securegate instance in proxy mode (frontend)
// and the main server, and between the gateway and telemt.

var proxyV2Sig = []byte("\r\n\r\n\x00\r\nQUIT\n")

// ProxyV2Header builds a PROXY v2 header announcing src -> dst (TCP or UDP addresses).
func ProxyV2Header(src, dst net.Addr) []byte {
	sIP, sPort := addrParts(src)
	dIP, dPort := addrParts(dst)
	var b bytes.Buffer
	b.Write(proxyV2Sig)
	b.WriteByte(0x21) // v2, PROXY
	if sIP == nil || dIP == nil {
		b.WriteByte(0x00)
		b.Write([]byte{0, 0})
		return b.Bytes()
	}
	if s4, d4 := sIP.To4(), dIP.To4(); s4 != nil && d4 != nil {
		b.WriteByte(0x11) // TCP over IPv4
		_ = binary.Write(&b, binary.BigEndian, uint16(12))
		b.Write(s4)
		b.Write(d4)
	} else {
		b.WriteByte(0x21) // TCP over IPv6
		_ = binary.Write(&b, binary.BigEndian, uint16(36))
		b.Write(sIP.To16())
		b.Write(dIP.To16())
	}
	_ = binary.Write(&b, binary.BigEndian, uint16(sPort))
	_ = binary.Write(&b, binary.BigEndian, uint16(dPort))
	return b.Bytes()
}

// ProxyV2Local builds a LOCAL PROXY v2 header (connection of the proxy itself, e.g. health check).
func ProxyV2Local() []byte {
	return append(append([]byte(nil), proxyV2Sig...), 0x20, 0x00, 0x00, 0x00)
}

func addrParts(a net.Addr) (net.IP, int) {
	switch v := a.(type) {
	case *net.TCPAddr:
		return v.IP, v.Port
	case *net.UDPAddr:
		return v.IP, v.Port
	}
	return nil, 0
}

// ReadProxyV2 reads a PROXY v2 header from c and returns the original client address
// (nil for a LOCAL command / unknown family: the caller keeps the socket address).
func ReadProxyV2(c net.Conn, timeout time.Duration) (net.Addr, error) {
	_ = c.SetReadDeadline(time.Now().Add(timeout))
	defer c.SetReadDeadline(time.Time{})
	h := make([]byte, 16)
	if _, err := io.ReadFull(c, h); err != nil {
		return nil, err
	}
	if !bytes.Equal(h[:12], proxyV2Sig) || h[12]>>4 != 2 {
		return nil, errors.New("no PROXY v2 header")
	}
	l := int(binary.BigEndian.Uint16(h[14:]))
	if l > 1024 {
		return nil, errors.New("PROXY v2 header too long")
	}
	body := make([]byte, l)
	if _, err := io.ReadFull(c, body); err != nil {
		return nil, err
	}
	if h[12]&0x0f == 0 { // LOCAL (health checks of the proxy itself)
		return nil, nil
	}
	switch h[13] >> 4 {
	case 1:
		if l >= 12 {
			return &net.TCPAddr{IP: net.IP(append([]byte(nil), body[0:4]...)), Port: int(binary.BigEndian.Uint16(body[8:]))}, nil
		}
	case 2:
		if l >= 36 {
			return &net.TCPAddr{IP: net.IP(append([]byte(nil), body[0:16]...)), Port: int(binary.BigEndian.Uint16(body[32:]))}, nil
		}
	}
	return nil, nil
}

// AddrConn overrides RemoteAddr (real client address received via PROXY protocol).
type AddrConn struct {
	net.Conn
	Remote net.Addr
}

func (c *AddrConn) RemoteAddr() net.Addr { return c.Remote }

// CloseWrite half-closes the underlying TCP connection (used by Pipe).
func (c *AddrConn) CloseWrite() error {
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return c.Conn.Close()
}

// NetConn returns the wrapped connection.
func (c *AddrConn) NetConn() net.Conn { return c.Conn }
