// Package frontend keeps the set of securegate proxy instances (PROXY=true servers) that relay
// clients to this main server. Connections from their IP addresses carry a PROXY v2 header with
// the real client address; their domains are served like DOMAIN (certificate, SNI, Host).
package frontend

import (
	"context"
	"log/slog"
	"net"
	"sort"
	"strings"
	"sync"
	"time"
)

type Set struct {
	domains []string     // lower-case frontend domains
	static  []*net.IPNet // PROXY_TRUSTED_IPS
	mu      sync.RWMutex
	ips     map[string]bool     // resolved A/AAAA of all domains (lookup set)
	byDom   map[string][]string // domain -> its last successfully resolved addresses
}

func New(domains []string, static []*net.IPNet) *Set {
	s := &Set{static: static, ips: map[string]bool{}, byDom: map[string][]string{}}
	for _, d := range domains {
		if d = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(d), ".")); d != "" {
			s.domains = append(s.domains, d)
		}
	}
	return s
}

// Enabled reports whether any frontend is configured.
func (s *Set) Enabled() bool { return s != nil && (len(s.domains) > 0 || len(s.static) > 0) }

// Domains returns the configured frontend domains.
func (s *Set) Domains() []string {
	if s == nil {
		return nil
	}
	return s.domains
}

// Trusted reports whether ip belongs to a frontend (its connections must start with PROXY v2).
func (s *Set) Trusted(ip net.IP) bool {
	if s == nil || ip == nil {
		return false
	}
	for _, n := range s.static {
		if n.Contains(ip) {
			return true
		}
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.ips[ip.String()]
}

// TrustedAddr is Trusted for a socket address.
func (s *Set) TrustedAddr(a net.Addr) bool {
	switch v := a.(type) {
	case *net.TCPAddr:
		return s.Trusted(v.IP)
	case *net.UDPAddr:
		return s.Trusted(v.IP)
	}
	return false
}

// Run resolves the frontend domains now and then every 5 minutes (DNS may change).
func (s *Set) Run(ctx context.Context) {
	if s == nil || len(s.domains) == 0 {
		return
	}
	s.resolve()
	t := time.NewTicker(5 * time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.resolve()
		}
	}
}

func (s *Set) resolve() {
	s.mu.RLock()
	byDom := make(map[string][]string, len(s.domains))
	for d, v := range s.byDom {
		byDom[d] = v
	}
	s.mu.RUnlock()
	changed := false
	for _, d := range s.domains {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		addrs, err := net.DefaultResolver.LookupIPAddr(ctx, d)
		cancel()
		if err != nil || len(addrs) == 0 {
			// keep this domain's previous addresses; other proxies are not affected
			slog.Warn("frontend: cannot resolve proxy domain (keeping its previous addresses)", "domain", d, "err", err, "kept", byDom[d])
			continue
		}
		list := make([]string, 0, len(addrs))
		for _, a := range addrs {
			list = append(list, a.IP.String())
		}
		sort.Strings(list)
		if strings.Join(list, ",") != strings.Join(byDom[d], ",") {
			changed = true
			slog.Info("frontend: proxy domain resolved", "domain", d, "ips", list)
		}
		byDom[d] = list
	}
	ips := map[string]bool{}
	for _, list := range byDom {
		for _, ip := range list {
			ips[ip] = true
		}
	}
	s.mu.Lock()
	s.byDom = byDom
	s.ips = ips
	s.mu.Unlock()
	if changed {
		slog.Info("frontend: trusted proxy addresses", "count", len(ips)+len(s.static))
	}
}
