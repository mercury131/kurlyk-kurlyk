// Package frontend keeps the set of securegate proxy instances (PROXY=true servers) that relay
// clients to this main server. Connections from their IP addresses carry a PROXY v2 header with
// the real client address; their domains are served like DOMAIN (certificate, SNI, Host).
package frontend

import (
	"context"
	"log/slog"
	"net"
	"strings"
	"sync"
	"time"
)

type Set struct {
	domains []string     // lower-case frontend domains
	static  []*net.IPNet // PROXY_TRUSTED_IPS
	mu      sync.RWMutex
	ips     map[string]bool // resolved A/AAAA of domains
}

func New(domains []string, static []*net.IPNet) *Set {
	s := &Set{static: static, ips: map[string]bool{}}
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
	ips := map[string]bool{}
	for _, d := range s.domains {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		addrs, err := net.DefaultResolver.LookupIPAddr(ctx, d)
		cancel()
		if err != nil {
			slog.Warn("frontend: cannot resolve proxy domain (keeping previous addresses)", "domain", d, "err", err)
			s.mu.RLock()
			for ip := range s.ips {
				ips[ip] = true
			}
			s.mu.RUnlock()
			continue
		}
		for _, a := range addrs {
			ips[a.IP.String()] = true
		}
	}
	s.mu.Lock()
	changed := len(ips) != len(s.ips)
	for ip := range ips {
		if !s.ips[ip] {
			changed = true
		}
	}
	s.ips = ips
	s.mu.Unlock()
	if changed {
		list := make([]string, 0, len(ips))
		for ip := range ips {
			list = append(list, ip)
		}
		slog.Info("frontend: trusted proxy addresses", "domains", s.domains, "ips", list)
	}
}
