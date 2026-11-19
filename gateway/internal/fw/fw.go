// Package fw picks the iptables backend(s) our NAT/FORWARD rules must go to.
//
// The container ships Debian's iptables, whose default `iptables` is the nf_tables backend. The kernel
// evaluates BOTH the nf_tables and the legacy (x_tables) rule sets, and a packet is dropped if EITHER
// drops it. On hosts where ufw / Docker / a WireGuard script use iptables-legacy (Ubuntu 20.04, older
// Debian/CentOS, or anything that ran `update-alternatives --set iptables iptables-legacy`) the host's
// FORWARD policy DROP lives in the legacy tables, so ACCEPT rules added only through the nft backend do
// not help: VPN clients connect, but none of their packets are forwarded. Fix: add our rules to every
// backend the host actually uses.
package fw

import (
	"bytes"
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"sync"
)

var (
	mu   sync.Mutex
	last string
)

// Backends returns the iptables binaries to install rules with: always the default "iptables"
// (nf_tables in the image), plus "iptables-legacy" when the host has an active legacy rule set
// (legacy tables loaded AND containing rules or a non-ACCEPT policy). Re-evaluated on every call
// (cheap), so a firewall enabled after our start (ufw enable) is picked up by periodic re-checks.
func Backends() []string {
	b := []string{"iptables"}
	if legacyInUse() {
		b = append(b, "iptables-legacy")
	}
	key := strings.Join(b, ",")
	mu.Lock()
	changed := key != last
	last = key
	mu.Unlock()
	if changed {
		slog.Info("fw: iptables backends", "use", key, "default", defaultMode())
	}
	return b
}

func defaultMode() string {
	out, _ := exec.Command("iptables", "-V").CombinedOutput()
	return strings.TrimSpace(string(out))
}

func legacyInUse() bool {
	// Without the legacy x_tables modules loaded nothing is filtered there; do not load them ourselves.
	names, err := os.ReadFile("/proc/net/ip_tables_names")
	if err != nil || len(bytes.TrimSpace(names)) == 0 {
		return false
	}
	if _, err := exec.LookPath("iptables-legacy-save"); err != nil {
		return false
	}
	out, err := exec.Command("iptables-legacy-save").Output()
	if err != nil {
		return false
	}
	for _, l := range strings.Split(string(out), "\n") {
		if strings.HasPrefix(l, "-A ") {
			return true
		}
		// built-in chain with a restrictive policy, e.g. ":FORWARD DROP [0:0]"
		if strings.HasPrefix(l, ":") {
			if f := strings.Fields(l); len(f) >= 2 && f[1] != "ACCEPT" && f[1] != "-" {
				return true
			}
		}
	}
	return false
}

// CleanupBackends returns every backend that may hold our rules (for removal): the default one and
// the legacy one whenever its tables are loaded.
func CleanupBackends() []string {
	b := []string{"iptables"}
	if names, err := os.ReadFile("/proc/net/ip_tables_names"); err == nil && len(bytes.TrimSpace(names)) > 0 {
		if _, err := exec.LookPath("iptables-legacy"); err == nil {
			b = append(b, "iptables-legacy")
		}
	}
	return b
}

// Rule is one iptables rule: Table ("" = filter), Chain and the match/target spec.
type Rule struct {
	Table string
	Chain string
	Spec  []string
}

func (r Rule) args(op string, pos ...string) []string {
	a := []string{"-w"}
	if r.Table != "" {
		a = append(a, "-t", r.Table)
	}
	a = append(a, op, r.Chain)
	a = append(a, pos...)
	return append(a, r.Spec...)
}

// Ensure inserts each missing rule at the top of its chain in every backend in use. It returns the
// number of rules (re)inserted and the first error.
func Ensure(rules []Rule) (int, error) {
	n := 0
	var first error
	for _, bin := range Backends() {
		for _, r := range rules {
			if exec.Command(bin, r.args("-C")...).Run() == nil {
				continue
			}
			if out, err := exec.Command(bin, r.args("-I", "1")...).CombinedOutput(); err != nil {
				if first == nil {
					first = &ruleErr{bin: bin, rule: r, out: strings.TrimSpace(string(out)), err: err}
				}
				continue
			}
			n++
		}
	}
	return n, first
}

// Remove deletes the rules (all duplicates) from every backend.
func Remove(rules []Rule) {
	for _, bin := range CleanupBackends() {
		for _, r := range rules {
			for i := 0; i < 8 && exec.Command(bin, r.args("-D")...).Run() == nil; i++ {
			}
		}
	}
}

type ruleErr struct {
	bin  string
	rule Rule
	out  string
	err  error
}

func (e *ruleErr) Error() string {
	return e.bin + " -t " + e.rule.Table + " " + e.rule.Chain + " " + strings.Join(e.rule.Spec, " ") + ": " + e.err.Error() + ": " + e.out
}
