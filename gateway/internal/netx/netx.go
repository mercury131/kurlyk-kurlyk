// Package netx contains small net.Conn / net.Listener helpers used by the traffic multiplexer.
package netx

import (
	"errors"
	"io"
	"net"
	"sync"
)

// ReaderConn is a net.Conn whose reads come from R (e.g. replayed bytes + bufio reader).
type ReaderConn struct {
	net.Conn
	R       io.Reader
	OnClose func()
	once    sync.Once
}

func (c *ReaderConn) Read(p []byte) (int, error) { return c.R.Read(p) }

func (c *ReaderConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() {
		if c.OnClose != nil {
			c.OnClose()
		}
	})
	return err
}

// ChanListener is a net.Listener fed by Push. Used to hand detected connections to
// http.Server, the TURN server, etc.
type ChanListener struct {
	ch     chan net.Conn
	addr   net.Addr
	closed chan struct{}
	once   sync.Once
}

func NewChanListener(addr net.Addr) *ChanListener {
	return &ChanListener{ch: make(chan net.Conn, 64), addr: addr, closed: make(chan struct{})}
}

func (l *ChanListener) Push(c net.Conn) {
	select {
	case l.ch <- c:
	case <-l.closed:
		c.Close()
	}
}

func (l *ChanListener) Accept() (net.Conn, error) {
	select {
	case c := <-l.ch:
		return c, nil
	case <-l.closed:
		return nil, net.ErrClosed
	}
}

func (l *ChanListener) Close() error {
	l.once.Do(func() { close(l.closed) })
	return nil
}

func (l *ChanListener) Addr() net.Addr { return l.addr }

// Pipe copies data in both directions and closes both ends when either side finishes.
func Pipe(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	cp := func(dst, src net.Conn) {
		defer wg.Done()
		_, _ = io.Copy(dst, src)
		if tc, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = tc.CloseWrite()
		} else {
			_ = dst.Close()
		}
	}
	go cp(a, b)
	go cp(b, a)
	wg.Wait()
	a.Close()
	b.Close()
}

// Reset closes a TCP connection with RST (no FIN handshake, no lingering).
func Reset(c net.Conn) {
	if ac, ok := c.(*AddrConn); ok {
		c = ac.Conn
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetLinger(0)
	}
	_ = c.Close()
}

// IsTimeout reports whether err is a network timeout.
func IsTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}
