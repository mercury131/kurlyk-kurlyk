// Package guard implements per-IP connection limits, rate limits, a strike system and bans
// (in-process and, optionally, in the kernel via nftables).
package guard

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type Config struct {
	MaxConnPerIP int
	RatePerMin   int
	Strikes      int
	BanBase      time.Duration
	Whitelist    []*net.IPNet
	Exempt       func(ip net.IP) bool // dynamic whitelist (proxy instances), may be nil
	NFT          bool
	NFTPorts     []int
	StateFile    string
}

type bucket struct {
	tokens float64
	last   time.Time
	warned time.Time
}

type strikeRec struct {
	points int
	first  time.Time
}

type BanRec struct {
	Until  time.Time `json:"until"`
	Count  int       `json:"count"`
	Reason string    `json:"reason"`
	Last   time.Time `json:"last"`
}

type Guard struct {
	cfg     Config
	mu      sync.Mutex
	conns   map[string]int
	buckets map[string]*bucket
	strikes map[string]*strikeRec
	bans    map[string]*BanRec
	nftOK   bool
	dirty   bool
}

const strikeWindow = 10 * time.Minute

func New(cfg Config) *Guard {
	g := &Guard{
		cfg:     cfg,
		conns:   map[string]int{},
		buckets: map[string]*bucket{},
		strikes: map[string]*strikeRec{},
		bans:    map[string]*BanRec{},
	}
	g.load()
	if cfg.NFT {
		if err := g.nftInit(); err != nil {
			slog.Warn("guard: nftables unavailable, using in-process bans only", "err", err)
		} else {
			g.nftOK = true
			now := time.Now()
			for ip, b := range g.bans {
				if b.Until.After(now) {
					g.nftBan(ip, b.Until.Sub(now))
				}
			}
		}
	}
	go g.loop()
	return g
}

func (g *Guard) exempt(ip string) bool {
	p := net.ParseIP(ip)
	if p == nil {
		return false
	}
	if p.IsLoopback() || isLocalAddr(p) {
		return true
	}
	if g.cfg.Exempt != nil && g.cfg.Exempt(p) {
		return true
	}
	for _, n := range g.cfg.Whitelist {
		if n.Contains(p) {
			return true
		}
	}
	return false
}

// local addresses of this host (cached): a connection from one of them originates on this host
// itself, e.g. telemt fetching the TLS front from the gateway with its upstream bound to the
// WireGuard uplink address (MTPROXY_UPSTREAM_IFACE). Spoofed external packets with a local source
// address are dropped by the kernel as martians.
var localAddrs struct {
	sync.Mutex
	at  time.Time
	set map[string]bool
}

func isLocalAddr(ip net.IP) bool {
	localAddrs.Lock()
	defer localAddrs.Unlock()
	if localAddrs.set == nil || time.Since(localAddrs.at) > 30*time.Second {
		set := map[string]bool{}
		if addrs, err := net.InterfaceAddrs(); err == nil {
			for _, a := range addrs {
				if n, ok := a.(*net.IPNet); ok {
					set[n.IP.String()] = true
				}
			}
		}
		localAddrs.set, localAddrs.at = set, time.Now()
	}
	return localAddrs.set[ip.String()]
}

// Admit is called for every new TCP connection. It returns a release func that must be called
// when the connection is closed.
func (g *Guard) Admit(ip string) (func(), bool) {
	if g.exempt(ip) {
		return func() {}, true
	}
	now := time.Now()
	g.mu.Lock()
	if b, ok := g.bans[ip]; ok && b.Until.After(now) {
		g.mu.Unlock()
		return nil, false
	}
	if g.conns[ip] >= g.cfg.MaxConnPerIP {
		g.mu.Unlock()
		g.Strike(ip, 1, "too many concurrent connections")
		return nil, false
	}
	bk := g.buckets[ip]
	rate := float64(g.cfg.RatePerMin) / 60.0
	burst := float64(g.cfg.RatePerMin) / 2
	if burst < 10 {
		burst = 10
	}
	if bk == nil {
		bk = &bucket{tokens: burst, last: now}
		g.buckets[ip] = bk
	}
	bk.tokens += now.Sub(bk.last).Seconds() * rate
	if bk.tokens > burst {
		bk.tokens = burst
	}
	bk.last = now
	if bk.tokens < 1 {
		strike := now.Sub(bk.warned) > 10*time.Second
		if strike {
			bk.warned = now
		}
		g.mu.Unlock()
		if strike {
			g.Strike(ip, 1, "connection rate exceeded")
		}
		return nil, false
	}
	bk.tokens--
	g.conns[ip]++
	g.mu.Unlock()
	var once sync.Once
	return func() {
		once.Do(func() {
			g.mu.Lock()
			if g.conns[ip]--; g.conns[ip] <= 0 {
				delete(g.conns, ip)
			}
			g.mu.Unlock()
		})
	}, true
}

// Banned reports whether ip is currently banned.
func (g *Guard) Banned(ip string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	b, ok := g.bans[ip]
	return ok && b.Until.After(time.Now())
}

// Strike adds penalty points to ip; once the threshold is reached the IP is banned.
func (g *Guard) Strike(ip string, points int, reason string) {
	if ip == "" || g.exempt(ip) {
		slog.Debug("guard: strike (exempt)", "ip", ip, "reason", reason)
		return
	}
	now := time.Now()
	g.mu.Lock()
	s := g.strikes[ip]
	if s == nil || now.Sub(s.first) > strikeWindow {
		s = &strikeRec{first: now}
		g.strikes[ip] = s
	}
	s.points += points
	total := s.points
	var banFor time.Duration
	if total >= g.cfg.Strikes {
		delete(g.strikes, ip)
		b := g.bans[ip]
		if b == nil || now.Sub(b.Last) > 7*24*time.Hour {
			b = &BanRec{}
			g.bans[ip] = b
		}
		b.Count++
		banFor = g.cfg.BanBase << uint(min(b.Count-1, 5))
		if banFor > 24*time.Hour {
			banFor = 24 * time.Hour
		}
		b.Until = now.Add(banFor)
		b.Last = now
		b.Reason = reason
		g.dirty = true
	}
	g.mu.Unlock()
	if banFor > 0 {
		slog.Warn("guard: IP banned", "ip", ip, "for", banFor.String(), "reason", reason)
		go g.save()
		if g.nftOK {
			g.nftBan(ip, banFor)
		}
	} else {
		slog.Info("guard: strike", "ip", ip, "points", total, "reason", reason)
	}
}

// Bans returns a snapshot of active bans.
func (g *Guard) Bans() map[string]BanRec {
	g.mu.Lock()
	defer g.mu.Unlock()
	out := map[string]BanRec{}
	now := time.Now()
	for k, v := range g.bans {
		if v.Until.After(now) {
			out[k] = *v
		}
	}
	return out
}

func (g *Guard) loop() {
	t := time.NewTicker(time.Minute)
	for range t.C {
		now := time.Now()
		g.mu.Lock()
		for ip, b := range g.buckets {
			if now.Sub(b.last) > 10*time.Minute {
				delete(g.buckets, ip)
			}
		}
		for ip, s := range g.strikes {
			if now.Sub(s.first) > strikeWindow {
				delete(g.strikes, ip)
			}
		}
		for ip, b := range g.bans {
			if now.Sub(b.Last) > 7*24*time.Hour && b.Until.Before(now) {
				delete(g.bans, ip)
				g.dirty = true
			}
		}
		dirty := g.dirty
		g.dirty = false
		g.mu.Unlock()
		if dirty {
			g.save()
		}
	}
}

// Reload re-reads the ban list from disk (used after `gateway unban`, triggered by SIGHUP).
func (g *Guard) Reload() {
	g.mu.Lock()
	g.bans = map[string]*BanRec{}
	g.strikes = map[string]*strikeRec{}
	g.load()
	n := len(g.bans)
	g.mu.Unlock()
	slog.Info("guard: ban list reloaded", "entries", n)
}

// AdminUnban removes ip ("all" = everything) from the state file and nftables.
// Runs as a separate CLI process; the daemon is then told to Reload via SIGHUP.
func AdminUnban(stateFile, ip string) (int, error) {
	bans := map[string]*BanRec{}
	if b, err := os.ReadFile(stateFile); err == nil {
		_ = json.Unmarshal(b, &bans)
	}
	removed := 0
	for k := range bans {
		if ip == "all" || k == ip {
			delete(bans, k)
			removed++
		}
	}
	b, _ := json.MarshalIndent(bans, "", " ")
	if err := os.WriteFile(stateFile, b, 0o600); err != nil {
		return removed, err
	}
	if ip == "all" {
		_ = exec.Command("nft", "flush", "set", "inet", "securegate", "banned4").Run()
		_ = exec.Command("nft", "flush", "set", "inet", "securegate", "banned6").Run()
	} else if p := net.ParseIP(ip); p != nil {
		set := "banned6"
		if p.To4() != nil {
			set = "banned4"
		}
		_ = exec.Command("nft", "delete", "element", "inet", "securegate", set, "{ "+ip+" }").Run()
	}
	return removed, nil
}

// AdminList returns bans from the state file.
func AdminList(stateFile string) map[string]*BanRec {
	bans := map[string]*BanRec{}
	if b, err := os.ReadFile(stateFile); err == nil {
		_ = json.Unmarshal(b, &bans)
	}
	return bans
}

func (g *Guard) load() {
	if g.cfg.StateFile == "" {
		return
	}
	b, err := os.ReadFile(g.cfg.StateFile)
	if err != nil {
		return
	}
	_ = json.Unmarshal(b, &g.bans)
	if g.bans == nil {
		g.bans = map[string]*BanRec{}
	}
}

func (g *Guard) save() {
	if g.cfg.StateFile == "" {
		return
	}
	g.mu.Lock()
	b, _ := json.MarshalIndent(g.bans, "", " ")
	g.mu.Unlock()
	_ = os.MkdirAll(filepath.Dir(g.cfg.StateFile), 0o700)
	tmp := g.cfg.StateFile + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		_ = os.Rename(tmp, g.cfg.StateFile)
	}
}

// Close persists state and removes kernel rules.
func (g *Guard) Close() {
	g.save()
	if g.nftOK {
		_ = exec.Command("nft", "delete", "table", "inet", "securegate").Run()
	}
}

func (g *Guard) nftInit() error {
	if _, err := exec.LookPath("nft"); err != nil {
		return err
	}
	_ = exec.Command("nft", "delete", "table", "inet", "securegate").Run()
	ports := make([]string, 0, len(g.cfg.NFTPorts))
	for _, p := range g.cfg.NFTPorts {
		ports = append(ports, fmt.Sprint(p))
	}
	script := fmt.Sprintf(`table inet securegate {
  set banned4 { type ipv4_addr; flags timeout; }
  set banned6 { type ipv6_addr; flags timeout; }
  chain input {
    type filter hook input priority -10; policy accept;
    ip saddr @banned4 tcp dport { %[1]s } drop
    ip6 saddr @banned6 tcp dport { %[1]s } drop
    ip saddr @banned4 udp dport { %[1]s } drop
    ip6 saddr @banned6 udp dport { %[1]s } drop
  }
}
`, strings.Join(ports, ", "))
	cmd := exec.Command("nft", "-f", "-")
	cmd.Stdin = strings.NewReader(script)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("%v: %s", err, out)
	}
	return nil
}

func (g *Guard) nftBan(ip string, d time.Duration) {
	p := net.ParseIP(ip)
	if p == nil {
		return
	}
	set := "banned6"
	if p.To4() != nil {
		set = "banned4"
		ip = p.To4().String()
	}
	secs := int(d.Seconds())
	if secs < 1 {
		return
	}
	el := fmt.Sprintf("{ %s timeout %ds }", ip, secs)
	if out, err := exec.Command("nft", "add", "element", "inet", "securegate", set, el).CombinedOutput(); err != nil {
		slog.Warn("guard: nft ban failed", "ip", ip, "err", err, "out", strings.TrimSpace(string(out)))
	}
}

// IPOf extracts the IP string from a net.Addr.
func IPOf(a net.Addr) string {
	switch v := a.(type) {
	case *net.TCPAddr:
		return v.IP.String()
	case *net.UDPAddr:
		return v.IP.String()
	}
	h, _, err := net.SplitHostPort(a.String())
	if err != nil {
		return a.String()
	}
	return h
}
