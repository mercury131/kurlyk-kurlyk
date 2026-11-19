// Package maskvpn is an experimental masquerading VPN transport (VPN_ENABLE=true).
//
// It lives INSIDE the normal HTTPS site on :443 (TLS is already terminated by the mux with our
// real certificate), so to any observer - and to an active prober that just opens the site in a
// browser - the server is an ordinary website (the cover site in internal/web). Only a client that
// proves knowledge of a user's secret (a time-bound HMAC carried in a cookie, Reality-like) is
// upgraded to the tunnel; every other request falls through to the real website. Because it is
// plain HTTPS/443, it also passes through the existing L4 proxy unchanged (PROXY v2 gives the real IP).
//
// Users: several secrets may be active at once (one per client), so clients are added and revoked
// individually (./sgctl vpn add|remove). Secrets come from VPN_SECRET, VPN_USERS and the files in
// data/maskvpn/users/<name>.secret (reloaded periodically, so sgctl needs no restart).
//
// Wire protocol (prototype, iteration 1):
//
//	1. Client -> GET <Path> with a browser-like WebSocket Upgrade AND cookie <authCookie>=<proof>.
//	2. Server finds the user whose key validates the proof; on success replies 101 Switching
//	   Protocols (a real Sec-WebSocket-Accept), hijacks the connection and speaks the binary tunnel
//	   below. On failure TryUpgrade returns false and the caller serves the cover site.
//	3. Handshake: client sends 32 random bytes, server sends 32 random bytes; both derive two
//	   directional keys with HKDF-SHA256(tunKey, salt=cRand||sRand). AEAD = ChaCha20-Poly1305 with
//	   an implicit per-direction 64-bit frame counter (nonce never travels on the wire).
//	4. Frames: uint16 big-endian ciphertext length, then the ciphertext (payload||16-byte tag).
//	   Iteration 1 payload = echo (proves the channel end to end, incl. through the proxy).
//	   Next iteration: payload = IP packets to a server-side TUN with NAT (like WireGuard).
package maskvpn

import (
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/hkdf"
)

// Path is the cover endpoint that carries the tunnel. It must look like an ordinary site endpoint;
// the caller (web.Handler) serves a benign response on it when the request is not an authed tunnel.
const Path = "/app/events"

const authCookie = "__Host-sess"

// wsMagic is the RFC 6455 GUID for Sec-WebSocket-Accept.
const wsMagic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

const (
	authSkew   = 120 * time.Second // accepted |now - ts|
	handshakeT = 15 * time.Second
	maxFrame   = 16384
)

// AuthKey and TunKey derive a user's two keys from their secret (also used by the test client).
func AuthKey(secret string) []byte { return derive(secret, "securegate-maskvpn-auth-v1") }
func TunKey(secret string) []byte  { return derive(secret, "securegate-maskvpn-tun-v1") }

func derive(secret, label string) []byte {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(label))
	return m.Sum(nil)
}

type user struct {
	name    string
	authKey []byte
	tunKey  []byte
}

// Loader returns a function giving the current name->secret map: the static users (VPN_SECRET,
// VPN_USERS) plus every data/maskvpn/users/<name>.secret file (so sgctl can add/remove without a
// restart). The static map is copied, so later callers do not mutate it.
func Loader(static map[string]string, dir string) func() map[string]string {
	base := map[string]string{}
	for k, v := range static {
		if v != "" {
			base[k] = v
		}
	}
	return func() map[string]string {
		m := make(map[string]string, len(base)+4)
		for k, v := range base {
			m[k] = v
		}
		ents, _ := os.ReadDir(dir)
		for _, e := range ents {
			if e.IsDir() || !strings.HasSuffix(e.Name(), ".secret") {
				continue
			}
			b, err := os.ReadFile(filepath.Join(dir, e.Name()))
			if err != nil {
				continue
			}
			if sec := strings.TrimSpace(string(b)); sec != "" {
				m[strings.TrimSuffix(e.Name(), ".secret")] = sec
			}
		}
		return m
	}
}

// Server upgrades authenticated requests to the tunnel. Users are refreshed from Load().
type Server struct {
	load   func() map[string]string // name -> secret
	router *Router                   // L3 routing (TUN + NAT); nil disables upgrades

	mu    sync.Mutex
	users []user
	seen  map[string]time.Time // anti-replay: salt -> expiry
}

// New builds a server. load returns the current name->secret map (VPN_SECRET + VPN_USERS + files).
// router does the L3 forwarding; if nil, authenticated clients are refused (the cover site is served).
func New(load func() map[string]string, router *Router) *Server {
	s := &Server{load: load, router: router, seen: map[string]time.Time{}}
	s.reload()
	go func() {
		t := time.NewTicker(15 * time.Second)
		defer t.Stop()
		for range t.C {
			s.reload()
		}
	}()
	return s
}

func (s *Server) reload() {
	m := s.load()
	us := make([]user, 0, len(m))
	for name, secret := range m {
		if len(secret) < 16 {
			continue
		}
		us = append(us, user{name: name, authKey: AuthKey(secret), tunKey: TunKey(secret)})
	}
	s.mu.Lock()
	s.users = us
	s.mu.Unlock()
}

// Count is the number of active users (for logs).
func (s *Server) Count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.users)
}

// MakeProof builds a cookie value for a user's authKey at unix time ts (exported for the client).
func MakeProof(authKey []byte, ts int64) string {
	data := make([]byte, 16)
	binary.BigEndian.PutUint64(data[:8], uint64(ts))
	_, _ = rand.Read(data[8:])
	tag := proofTag(authKey, data)
	return base64.RawURLEncoding.EncodeToString(append(data, tag...))
}

func proofTag(authKey, data []byte) []byte {
	m := hmac.New(sha256.New, authKey)
	m.Write([]byte("proof"))
	m.Write(data)
	return m.Sum(nil)[:16]
}

// matchProof returns the user whose key validates the cookie and guards against salt replay.
func (s *Server) matchProof(v string) *user {
	raw, err := base64.RawURLEncoding.DecodeString(v)
	if err != nil || len(raw) != 32 {
		return nil
	}
	data, tag := raw[:16], raw[16:]
	ts := int64(binary.BigEndian.Uint64(data[:8]))
	now := time.Now()
	if d := now.Sub(time.Unix(ts, 0)); d > authSkew || d < -authSkew {
		return nil
	}
	s.mu.Lock()
	users := s.users
	s.mu.Unlock()
	var matched *user
	for i := range users {
		if subtle.ConstantTimeCompare(proofTag(users[i].authKey, data), tag) == 1 {
			matched = &users[i]
			break
		}
	}
	if matched == nil {
		return nil
	}
	salt := string(data[8:])
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.seen) > 4096 {
		for k, exp := range s.seen {
			if now.After(exp) {
				delete(s.seen, k)
			}
		}
	}
	if exp, ok := s.seen[salt]; ok && now.Before(exp) {
		return nil // replay
	}
	s.seen[salt] = now.Add(authSkew * 2)
	return matched
}

// authed returns the matching user (or nil) for the request's cookie.
func (s *Server) authed(r *http.Request) *user {
	c, err := r.Cookie(authCookie)
	if err != nil {
		return nil
	}
	return s.matchProof(c.Value)
}

// TryUpgrade handles an authenticated tunnel request. It returns true if it took over the
// connection (the caller must then write nothing more). It returns false for any request that is
// not a valid tunnel, so the caller serves the cover site instead.
func (s *Server) TryUpgrade(w http.ResponseWriter, r *http.Request, ip string) bool {
	if r.URL.Path != Path || s.router == nil {
		return false
	}
	u := s.authed(r)
	if u == nil {
		return false
	}
	wsKey := r.Header.Get("Sec-WebSocket-Key")
	if wsKey == "" {
		return false // not our client; let the cover site answer
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		return false
	}
	conn, brw, err := hj.Hijack()
	if err != nil {
		return false
	}
	defer conn.Close()

	// Real WebSocket 101 response (masquerade: looks like any ws:// upgrade).
	acc := sha1.Sum([]byte(wsKey + wsMagic))
	resp := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\nConnection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + base64.StdEncoding.EncodeToString(acc[:]) + "\r\n\r\n"
	if _, err := brw.WriteString(resp); err != nil || brw.Flush() != nil {
		return true
	}

	_ = s.tunnel(conn, brw.Reader, u, ip) // logged inside
	return true
}

const idleTimeout = 120 * time.Second

// tunnel runs the key exchange, assigns the client an address and forwards IPv4 packets between the
// client's connection and the TUN device (the kernel then NATs them to the internet).
func (s *Server) tunnel(conn net.Conn, r io.Reader, u *user, ip string) error {
	_ = conn.SetDeadline(time.Now().Add(handshakeT))
	cRand := make([]byte, 32)
	if _, err := io.ReadFull(r, cRand); err != nil {
		return err
	}
	sRand := make([]byte, 32)
	if _, err := rand.Read(sRand); err != nil {
		return err
	}
	if _, err := conn.Write(sRand); err != nil {
		return err
	}
	rx, tx, err := deriveAEAD(u.tunKey, cRand, sRand, true)
	if err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Time{})

	out := make(chan []byte, 256)
	client, cfg, err := s.router.Allocate(out)
	if err != nil {
		return err
	}
	defer s.router.Release(client)
	slog.Info("maskvpn: tunnel up", "ip", ip, "user", u.name, "addr", client.ip.String())

	// Traffic counters: logged when the tunnel ends, and a one-time warning if the client keeps sending
	// but nothing ever comes back (= the host drops forwarded packets: firewall / NAT problem).
	var upPkts, upBytes, downPkts, downBytes atomic.Int64
	start := time.Now()
	var tunErr error
	defer func() {
		reason := "closed"
		if tunErr != nil {
			reason = tunErr.Error()
		}
		slog.Info("maskvpn: tunnel down", "ip", ip, "user", u.name, "addr", client.ip.String(),
			"duration", time.Since(start).Round(time.Second).String(),
			"up_pkts", upPkts.Load(), "up_bytes", upBytes.Load(),
			"down_pkts", downPkts.Load(), "down_bytes", downBytes.Load(), "reason", reason)
	}()
	warned := false

	// Writer goroutine: owns tx and the send counter, serializes all writes to conn, and sends a
	// keepalive so the outer TCP stays warm through the proxy / NAT.
	done := make(chan struct{})
	go func() {
		var tn uint64
		ka := time.NewTicker(20 * time.Second)
		defer ka.Stop()
		writeFrame := func(pt []byte) bool {
			ct := tx.Seal(nil, nonce(tn), pt, nil)
			tn++
			frame := make([]byte, 2+len(ct))
			binary.BigEndian.PutUint16(frame, uint16(len(ct)))
			copy(frame[2:], ct)
			_, e := conn.Write(frame)
			return e == nil
		}
		for {
			select {
			case pt := <-out:
				if len(pt) > 0 && pt[0] == opIP {
					downPkts.Add(1)
					downBytes.Add(int64(len(pt) - 1))
				}
				if !writeFrame(pt) {
					conn.Close()
					return
				}
			case <-ka.C:
				if !writeFrame([]byte{opKeepalive}) {
					conn.Close()
					return
				}
			case <-done:
				return
			}
		}
	}()
	defer close(done)

	// First frame to the client: its address, gateway, DNS, MTU and routes.
	cfgJSON, _ := json.Marshal(cfg)
	out <- append([]byte{opConfig}, cfgJSON...)

	// Reader loop: client -> TUN.
	var rn uint64
	hdr := make([]byte, 2)
	buf := make([]byte, maxFrame)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(idleTimeout))
		if _, err := io.ReadFull(r, hdr); err != nil {
			tunErr = err
			return err
		}
		n := int(binary.BigEndian.Uint16(hdr))
		if n == 0 || n > maxFrame {
			tunErr = errors.New("bad frame length")
			return tunErr
		}
		if _, err := io.ReadFull(r, buf[:n]); err != nil {
			tunErr = err
			return err
		}
		pt, err := open(rx, &rn, buf[:n])
		if err != nil {
			tunErr = fmt.Errorf("decrypt frame %d: %w (client frame order/counter mismatch)", rn, err)
			return tunErr
		}
		if len(pt) == 0 {
			continue
		}
		switch pt[0] {
		case opIP:
			upPkts.Add(1)
			upBytes.Add(int64(len(pt) - 1))
			s.router.WriteToTUN(pt[1:])
			if !warned && upPkts.Load() >= 30 && downPkts.Load() == 0 && time.Since(start) > 5*time.Second {
				warned = true
				slog.Warn("maskvpn: client sends packets but NOTHING comes back - the host does not forward/NAT "+
					"the VPN subnet (firewall: ufw/iptables-legacy/nftables FORWARD policy, ip_forward, VPN_SUBNET overlap)",
					"ip", ip, "user", u.name, "addr", client.ip.String(), "up_pkts", upPkts.Load())
			}
		case opKeepalive, opConfig:
			// keepalive: nothing; config from client: ignored
		}
	}
}

// deriveAEAD builds the two directional ciphers. server=true => rx uses the client->server key.
func deriveAEAD(key, cRand, sRand []byte, server bool) (rx, tx cipher.AEAD, err error) {
	salt := append(append([]byte{}, cRand...), sRand...)
	h := hkdf.New(sha256.New, key, salt, []byte("securegate-maskvpn-v1"))
	km := make([]byte, 64)
	if _, err := io.ReadFull(h, km); err != nil {
		return nil, nil, err
	}
	c2s, err := chacha20poly1305.New(km[:32])
	if err != nil {
		return nil, nil, err
	}
	s2c, err := chacha20poly1305.New(km[32:])
	if err != nil {
		return nil, nil, err
	}
	if server {
		return c2s, s2c, nil
	}
	return s2c, c2s, nil
}

func nonce(ctr uint64) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint64(n[4:], ctr)
	return n
}

func open(a cipher.AEAD, ctr *uint64, ct []byte) ([]byte, error) {
	pt, err := a.Open(nil, nonce(*ctr), ct, nil)
	if err != nil {
		return nil, err
	}
	*ctr++
	return pt, nil
}
