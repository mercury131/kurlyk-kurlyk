//go:build linux

// L3 routing for the masquerading VPN: a TUN device (sgvpn0) with NAT to the internet, exactly like
// the WireGuard container but driven from Go. Each connected client is assigned an address from the
// pool; packets the kernel puts on the TUN for a client's address are encrypted and sent down that
// client's tunnel, and packets a client sends are written to the TUN (the kernel then NATs them out).
package maskvpn

import (
	"encoding/binary"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"sync"
	"time"

	"golang.org/x/sys/unix"

	"securegate/internal/fw"
)

const (
	tunIface = "sgvpn0"
	tunMTU   = 1380 // room for TLS + WebSocket + AEAD overhead inside the outer TCP/443
	ruleTag  = "securegate-vpn"
)

// opcodes inside the AEAD plaintext (first byte).
const (
	opConfig    = 0x01 // server->client: tunnel config (JSON)
	opIP        = 0x02 // either direction: one IPv4 packet
	opKeepalive = 0x03
)

// tunnelConfig is sent to the client right after the key exchange.
type tunnelConfig struct {
	Address string   `json:"address"` // e.g. "10.18.0.2/24"
	Gateway string   `json:"gateway"` // server address on the TUN
	DNS     []string `json:"dns"`
	MTU     int      `json:"mtu"`
	Routes  []string `json:"routes"` // what the client sends into the tunnel
}

// Router owns the TUN device and the client table.
type Router struct {
	tun      *os.File
	writeMu  sync.Mutex
	serverIP net.IP
	ipnet    *net.IPNet
	dns      []string
	stop      chan struct{}
	closeOnce sync.Once

	mu    sync.Mutex
	used  map[byte]*tunClient // last octet -> client
	byIP  map[uint32]*tunClient
	nextN byte
}

type tunClient struct {
	ip     net.IP
	ipKey  uint32
	out    chan []byte // plaintext frames (opcode + data) queued to this client's writer
	mu     sync.Mutex
	closed bool
}

// NewRouter creates and configures the TUN device and installs NAT rules. subnetCIDR like
// "10.18.0.0/24"; the server takes the first address (.1), clients get .2 .. .254.
func NewRouter(subnetCIDR string, dns []string) (*Router, error) {
	ip, ipnet, err := net.ParseCIDR(subnetCIDR)
	if err != nil {
		return nil, fmt.Errorf("VPN subnet %q: %w", subnetCIDR, err)
	}
	server := nextIP(ipnet.IP, 1)
	f, err := openTUN(tunIface)
	if err != nil {
		return nil, err
	}
	r := &Router{tun: f, serverIP: server, ipnet: ipnet, dns: dns,
		used: map[byte]*tunClient{}, byIP: map[uint32]*tunClient{}, nextN: 2, stop: make(chan struct{})}
	_ = ip
	if err := r.configure(); err != nil {
		f.Close()
		return nil, err
	}
	go r.readLoop()
	go r.keepFirewall()
	slog.Info("maskvpn: TUN ready", "iface", tunIface, "server", server.String(), "subnet", subnetCIDR, "mtu", tunMTU)
	return r, nil
}

func (r *Router) configure() error {
	ones, _ := r.ipnet.Mask.Size()
	run := func(args ...string) error {
		out, err := exec.Command(args[0], args[1:]...).CombinedOutput()
		if err != nil {
			return fmt.Errorf("%v: %v (%s)", args, err, out)
		}
		return nil
	}
	_ = run("sysctl", "-w", "net.ipv4.ip_forward=1")
	if err := run("ip", "addr", "add", fmt.Sprintf("%s/%d", r.serverIP, ones), "dev", tunIface); err != nil {
		// already present on a restart is fine
		slog.Debug("maskvpn: ip addr add", "err", err)
	}
	if err := run("ip", "link", "set", tunIface, "mtu", fmt.Sprintf("%d", tunMTU), "up"); err != nil {
		return err
	}
	r.firewall("-D") // clean stale rules first (any backend)
	r.firewall("-I")
	return nil
}

// rules are the NAT/forward rules for the VPN subnet, tagged so they are easy to find and do not clash
// with the host's own rules. Mirrors the WireGuard container.
func (r *Router) rules() []fw.Rule {
	sub := r.ipnet.String()
	tag := []string{"-m", "comment", "--comment", ruleTag}
	mk := func(table, chain string, spec ...string) fw.Rule {
		return fw.Rule{Table: table, Chain: chain, Spec: append(spec, tag...)}
	}
	return []fw.Rule{
		mk("nat", "POSTROUTING", "-s", sub, "!", "-d", sub, "-j", "MASQUERADE"),
		mk("", "FORWARD", "-i", tunIface, "-j", "ACCEPT"),
		mk("", "FORWARD", "-o", tunIface, "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT"),
		mk("mangle", "FORWARD", "-i", tunIface, "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu"),
		mk("mangle", "FORWARD", "-o", tunIface, "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu"),
	}
}

// firewall installs (-I) or removes (-D) the rules in every iptables backend the host uses (see fw).
func (r *Router) firewall(op string) {
	if op != "-I" {
		fw.Remove(r.rules())
		return
	}
	if n, err := fw.Ensure(r.rules()); err != nil {
		slog.Warn("maskvpn: iptables insert failed", "err", err)
	} else if n > 0 {
		slog.Info("maskvpn: NAT/forward rules installed", "rules", n, "backends", fw.Backends())
	}
}

// keepFirewall re-checks the rules every minute: a firewall reload (ufw enable/reload, docker
// restart, a WireGuard script) may flush or bypass them, and ufw may be enabled after our start.
func (r *Router) keepFirewall() {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-r.stop:
			return
		case <-t.C:
			if n, err := fw.Ensure(r.rules()); err != nil {
				slog.Warn("maskvpn: iptables re-check failed", "err", err)
			} else if n > 0 {
				slog.Warn("maskvpn: NAT/forward rules were missing, re-installed", "rules", n)
			}
		}
	}
}

// Close removes the firewall rules and the interface.
func (r *Router) Close() {
	r.closeOnce.Do(func() { close(r.stop) })
	r.firewall("-D")
	if r.tun != nil {
		r.tun.Close()
	}
	_ = exec.Command("ip", "link", "del", tunIface).Run()
}

// Allocate assigns a free address to a new client and returns its config.
func (r *Router) Allocate(send chan []byte) (*tunClient, tunnelConfig, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	ones, _ := r.ipnet.Mask.Size()
	for i := 0; i < 253; i++ {
		n := r.nextN
		r.nextN++
		if r.nextN < 2 {
			r.nextN = 2
		}
		if n < 2 || n == 255 {
			continue
		}
		if _, ok := r.used[n]; ok {
			continue
		}
		ip := nextIP(r.ipnet.IP, int(n))
		c := &tunClient{ip: ip, ipKey: ip2u32(ip), out: send}
		r.used[n] = c
		r.byIP[c.ipKey] = c
		cfg := tunnelConfig{
			Address: fmt.Sprintf("%s/%d", ip, ones),
			Gateway: r.serverIP.String(),
			DNS:     r.dns,
			MTU:     tunMTU,
			Routes:  []string{"0.0.0.0/0"},
		}
		return c, cfg, nil
	}
	return nil, tunnelConfig{}, fmt.Errorf("address pool exhausted")
}

// Release frees a client's address.
func (r *Router) Release(c *tunClient) {
	if c == nil {
		return
	}
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
	r.mu.Lock()
	delete(r.byIP, c.ipKey)
	delete(r.used, c.ip.To4()[3])
	r.mu.Unlock()
}

// WriteToTUN injects a client's IP packet into the kernel.
func (r *Router) WriteToTUN(pkt []byte) {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return // IPv4 only in the prototype
	}
	r.writeMu.Lock()
	_, _ = r.tun.Write(pkt)
	r.writeMu.Unlock()
}

// readLoop reads packets the kernel routes to the TUN and hands each to the owning client.
func (r *Router) readLoop() {
	buf := make([]byte, 65535)
	for {
		n, err := r.tun.Read(buf)
		if err != nil {
			return
		}
		if n < 20 || buf[0]>>4 != 4 {
			continue
		}
		dst := binary.BigEndian.Uint32(buf[16:20])
		r.mu.Lock()
		c := r.byIP[dst]
		r.mu.Unlock()
		if c == nil {
			continue
		}
		pkt := make([]byte, 1+n)
		pkt[0] = opIP
		copy(pkt[1:], buf[:n])
		c.send(pkt)
	}
}

// send queues a plaintext frame to the client's writer without blocking (drops on congestion).
func (c *tunClient) send(frame []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	select {
	case c.out <- frame:
	default: // link congested: drop, like a real lossy network
	}
}

// ---- helpers ----

func openTUN(name string) (*os.File, error) {
	fd, err := unix.Open("/dev/net/tun", unix.O_RDWR, 0)
	if err != nil {
		return nil, fmt.Errorf("open /dev/net/tun (needs privileged + /dev/net/tun): %w", err)
	}
	req, err := unix.NewIfreq(name)
	if err != nil {
		unix.Close(fd)
		return nil, err
	}
	req.SetUint16(unix.IFF_TUN | unix.IFF_NO_PI)
	if err := unix.IoctlIfreq(fd, unix.TUNSETIFF, req); err != nil {
		unix.Close(fd)
		return nil, fmt.Errorf("TUNSETIFF %s: %w", name, err)
	}
	return os.NewFile(uintptr(fd), name), nil
}

func nextIP(base net.IP, add int) net.IP {
	b := base.To4()
	v := binary.BigEndian.Uint32(b) + uint32(add)
	out := make(net.IP, 4)
	binary.BigEndian.PutUint32(out, v)
	return out
}

func ip2u32(ip net.IP) uint32 { return binary.BigEndian.Uint32(ip.To4()) }
