// Package config loads gateway settings from environment variables (docker compose .env)
// and an optional YAML file mounted into the container (config/users.yml).
package config

import (
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

type SSTPUser struct {
	User     string `yaml:"user"`
	Password string `yaml:"password"`
}

// MTAltImpl is an alternative MTProxy implementation (mtprotoproxy / teleproxy).
type MTAltImpl struct {
	Name         string // "mtprotoproxy" | "teleproxy" (= container and data/mtproxy/<name>/)
	Enable       bool
	Port         int // public port (0 = disabled)
	InternalPort int // 127.0.0.1 port of the implementation
}

type Config struct {
	Domain      string
	ACMEEmail   string
	TLSMode     string // auto | letsencrypt | selfsigned
	ACMEStaging bool
	ACMEDir     string // custom ACME directory URL (other CA / test CA)
	HTTPAddr    string
	HTTPSAddr   string
	HTTPSPort   int
	DataDir     string
	UsersFile   string
	SNIStrict   bool
	LogLevel    string

	SSTPEnable   bool
	SSTPUsers    []SSTPUser
	SSTPLocalIP  string
	SSTPPool     string
	SSTPDNS      []string
	SSTPNAT      bool
	SSTPMTU      int
	SSTPPPPDebug bool
	PPPDPath     string

	MTEnable       bool
	MTUsers        map[string]string // name -> 32 hex secret
	MTInternalPort int
	MTConfigDir    string
	MTAdTag        string
	// MTUpstreamIface: network interface for telemt's outgoing connections to Telegram
	// (e.g. the WireGuard uplink sgup0 to the main server). Empty = default route.
	MTUpstreamIface string
	// Alternative MTProxy implementations on their own public ports, same users (MTUsers).
	// Port 0 = disabled. The gateway listens on the public port (same detector as :443) and
	// forwards authenticated Telegram clients to 127.0.0.1:<internal port>.
	MTAlt []MTAltImpl

	ChatEnable        bool
	ChatSecret        string
	ChatMaxUsers      int
	ChatAllowedPhones []string
	ChatMaxFileMB     int
	ChatFilesMaxGB    int
	ChatFileTTL       time.Duration
	ChatQueueTTL      time.Duration

	TURNEnable  bool
	PublicIP    string
	TURNUDPPort int
	TURNMinPort int
	TURNMaxPort int
	TURNSecret  string

	GuardMaxConnPerIP int
	GuardRatePerMin   int
	GuardStrikes      int
	GuardBan          time.Duration
	GuardWhitelist    []*net.IPNet
	GuardNFT          bool

	// Masquerading VPN (experimental, VPN_ENABLE=false by default): a custom encrypted tunnel
	// hidden inside normal HTTPS to the cover website on :443. Unauthenticated requests get the
	// real website (Reality-like fallback), so an active prober only sees an ordinary site.
	VPNEnable   bool
	VPNSecret   string            // single shared secret (user "default"); back-compatible
	VPNUsers    map[string]string // name -> secret (VPN_USERS); per-client add/revoke
	VPNUsersDir string            // data/maskvpn/users: <name>.secret files added by sgctl at runtime
	VPNSubnet   string            // TUN client pool (server = first address)
	VPNDNS      []string          // DNS pushed to VPN clients

	// Proxy mode (PROXY=true): this instance only relays 443/tcp, 80/tcp and the TURN UDP port
	// to another securegate server (DOMAIN_TO_PROXY); all other settings are ignored.
	ProxyMode     bool
	ProxyUpstream string // host (or host:port of the upstream HTTPS port)
	ProxyUDP      bool
	// WireGuard UDP port (main server: wireguard container; proxy: relayed to the main server)
	WGEnable bool
	WGPort   int
	// Main server side: proxy instances relaying to us.
	ProxyFrontends []string     // their domains (certificate + SNI + Host, IPs trusted)
	ProxyTrusted   []*net.IPNet // extra trusted source addresses (PROXY_TRUSTED_IPS)
}

// Hosts returns all host names served by this server: DOMAIN and the proxy frontend domains.
func (c *Config) Hosts() []string {
	return append([]string{c.Domain}, c.ProxyFrontends...)
}

// cidrOf parses "1.2.3.4", "2001:db8::1" or "10.0.0.0/24"; nil if w is not an address.
func cidrOf(w string) *net.IPNet {
	if ip := net.ParseIP(w); ip != nil {
		bits := 32
		if ip.To4() == nil {
			bits = 128
		}
		return &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)}
	}
	if _, n, err := net.ParseCIDR(w); err == nil {
		return n
	}
	return nil
}

func parseCIDRs(key string) ([]*net.IPNet, error) {
	var out []*net.IPNet
	for _, w := range splitList(os.Getenv(key)) {
		if !strings.Contains(w, "/") {
			if strings.Contains(w, ":") {
				w += "/128"
			} else {
				w += "/32"
			}
		}
		_, n, err := net.ParseCIDR(w)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", key, err)
		}
		out = append(out, n)
	}
	return out, nil
}

type usersFile struct {
	SSTPUsers         []SSTPUser        `yaml:"sstp_users"`
	MTProxyUsers      map[string]string `yaml:"mtproxy_users"`
	ChatAllowedPhones []string          `yaml:"chat_allowed_phones"`
}

func env(key, def string) string {
	if v, ok := os.LookupEnv(key); ok && strings.TrimSpace(v) != "" {
		return strings.TrimSpace(v)
	}
	return def
}

func envInt(key string, def int) int {
	v := env(key, "")
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func envBool(key string, def bool) bool {
	v := strings.ToLower(env(key, ""))
	switch v {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	}
	return def
}

func splitList(v string) []string {
	var out []string
	for _, p := range strings.FieldsFunc(v, func(r rune) bool { return r == ',' || r == ';' || r == '\n' }) {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

var hex32 = regexp.MustCompile(`^[0-9a-fA-F]{32}$`)
var userName = regexp.MustCompile(`^[A-Za-z0-9_.@-]{1,64}$`)

// Load reads and validates the configuration.
func Load() (*Config, error) {
	c := &Config{
		Domain:      strings.ToLower(env("DOMAIN", "")),
		ACMEEmail:   env("ACME_EMAIL", ""),
		TLSMode:     strings.ToLower(env("TLS_MODE", "auto")),
		ACMEStaging: envBool("ACME_STAGING", false),
		ACMEDir:     env("ACME_DIRECTORY", ""),
		HTTPAddr:    ":" + env("HTTP_PORT", "80"),
		HTTPSPort:   envInt("HTTPS_PORT", 443),
		DataDir:     env("DATA_DIR", "/data"),
		UsersFile:   env("USERS_FILE", "/config/users.yml"),
		SNIStrict:   strings.ToLower(env("SNI_POLICY", "lenient")) == "strict",
		LogLevel:    strings.ToLower(env("LOG_LEVEL", "info")),

		SSTPEnable:   envBool("SSTP_ENABLE", true),
		SSTPLocalIP:  env("SSTP_LOCAL_IP", "192.168.17.1"),
		SSTPPool:     env("SSTP_POOL", "192.168.17.0/24"),
		SSTPDNS:      splitList(env("SSTP_DNS", "8.8.8.8,8.8.4.4")),
		SSTPNAT:      envBool("SSTP_NAT", true),
		SSTPMTU:      envInt("SSTP_MTU", 1400),
		SSTPPPPDebug: envBool("SSTP_PPP_DEBUG", false),
		PPPDPath:     env("PPPD_PATH", "/usr/sbin/pppd"),

		MTEnable:        envBool("MTPROXY_ENABLE", true),
		MTUsers:         map[string]string{},
		MTInternalPort:  envInt("MTPROXY_INTERNAL_PORT", 8443),
		MTConfigDir:     env("MTPROXY_CONFIG_DIR", "/data/mtproxy"),
		MTAdTag:         env("MTPROXY_AD_TAG", ""),
		MTUpstreamIface: strings.TrimSpace(env("MTPROXY_UPSTREAM_IFACE", "")),
		MTAlt: []MTAltImpl{
			{Name: "mtprotoproxy", Enable: envBool("MTPROTOPROXY_ENABLE", true),
				Port: envInt("MTPROTOPROXY_PORT", 2053), InternalPort: envInt("MTPROTOPROXY_INTERNAL_PORT", 8444)},
			{Name: "teleproxy", Enable: envBool("TELEPROXY_ENABLE", true),
				Port: envInt("TELEPROXY_PORT", 2083), InternalPort: envInt("TELEPROXY_INTERNAL_PORT", 8445)},
		},
		WGEnable: envBool("WG_ENABLE", true),
		WGPort:   envInt("WG_PORT", 51820),

		VPNEnable:   envBool("VPN_ENABLE", false),
		VPNSecret:   env("VPN_SECRET", ""),
		VPNUsers:    map[string]string{},
		VPNUsersDir: env("VPN_USERS_DIR", "/data/maskvpn/users"),
		VPNSubnet:   env("VPN_SUBNET", "10.18.0.0/24"),
		VPNDNS:      splitList(env("VPN_DNS", "8.8.8.8,8.8.4.4")),

		ChatEnable:     envBool("CHAT_ENABLE", true),
		ChatSecret:     env("CHAT_SECRET", ""),
		ChatMaxUsers:   envInt("CHAT_MAX_USERS", 100),
		ChatMaxFileMB:  envInt("CHAT_MAX_FILE_MB", 100),
		ChatFilesMaxGB: envInt("CHAT_FILES_MAX_GB", 10),
		ChatFileTTL:    time.Duration(envInt("CHAT_FILE_TTL_HOURS", 168)) * time.Hour,
		ChatQueueTTL:   time.Duration(envInt("CHAT_QUEUE_TTL_HOURS", 720)) * time.Hour,

		TURNEnable:  envBool("TURN_ENABLE", true),
		PublicIP:    env("PUBLIC_IP", ""),
		TURNUDPPort: envInt("TURN_UDP_PORT", 3478),
		TURNMinPort: envInt("TURN_MIN_PORT", 49160),
		TURNMaxPort: envInt("TURN_MAX_PORT", 49200),
		TURNSecret:  env("TURN_SECRET", ""),

		GuardMaxConnPerIP: envInt("GUARD_MAX_CONN_PER_IP", 40),
		GuardRatePerMin:   envInt("GUARD_RATE_PER_MIN", 120),
		GuardStrikes:      envInt("GUARD_STRIKES", 5),
		GuardBan:          time.Duration(envInt("GUARD_BAN_MINUTES", 60)) * time.Minute,
		GuardNFT:          envBool("GUARD_NFTABLES", true),
	}
	c.HTTPSAddr = ":" + strconv.Itoa(c.HTTPSPort)

	var err error
	if c.GuardWhitelist, err = parseCIDRs("GUARD_WHITELIST"); err != nil {
		return nil, err
	}

	// ---- proxy mode: only the relay settings matter ----
	c.ProxyMode = envBool("PROXY", false) || envBool("PROXY_MODE", false)
	if c.ProxyMode {
		up := strings.ToLower(env("DOMAIN_TO_PROXY", ""))
		up = strings.TrimSuffix(strings.TrimPrefix(strings.TrimPrefix(up, "https://"), "http://"), "/")
		if up == "" {
			return nil, fmt.Errorf("PROXY=true requires DOMAIN_TO_PROXY (domain of the main securegate server)")
		}
		if c.Domain != "" && strings.EqualFold(up, c.Domain) {
			return nil, fmt.Errorf("DOMAIN_TO_PROXY must be the MAIN server, not this proxy's own DOMAIN")
		}
		c.ProxyUpstream = up
		c.ProxyUDP = envBool("PROXY_UDP", true)
		return c, nil
	}
	if c.ProxyTrusted, err = parseCIDRs("PROXY_TRUSTED_IPS"); err != nil {
		return nil, err
	}
	// PROXY_FRONTENDS: domains of the proxy instances (several allowed, comma separated).
	// An IP/CIDR in this list is treated like PROXY_TRUSTED_IPS (proxy without a domain).
	seen := map[string]bool{c.Domain: true}
	for _, d := range splitList(os.Getenv("PROXY_FRONTENDS")) {
		d = strings.ToLower(strings.TrimSuffix(strings.TrimPrefix(strings.TrimPrefix(d, "https://"), "http://"), "."))
		if n := cidrOf(d); n != nil {
			c.ProxyTrusted = append(c.ProxyTrusted, n)
			continue
		}
		if seen[d] {
			continue
		}
		seen[d] = true
		c.ProxyFrontends = append(c.ProxyFrontends, d)
	}

	if c.Domain == "" {
		return nil, fmt.Errorf("DOMAIN is required")
	}
	switch c.TLSMode {
	case "auto", "letsencrypt", "selfsigned":
	default:
		return nil, fmt.Errorf("TLS_MODE must be auto|letsencrypt|selfsigned")
	}

	// SSTP users from env: "user:password,user2:password2"
	for _, e := range splitList(os.Getenv("SSTP_USERS")) {
		i := strings.Index(e, ":")
		if i <= 0 {
			return nil, fmt.Errorf("SSTP_USERS entry %q must be user:password", e)
		}
		c.SSTPUsers = append(c.SSTPUsers, SSTPUser{User: e[:i], Password: e[i+1:]})
	}
	// MTProxy users from env: "name:hexsecret,..."
	for _, e := range splitList(os.Getenv("MTPROXY_USERS")) {
		i := strings.Index(e, ":")
		if i <= 0 {
			return nil, fmt.Errorf("MTPROXY_USERS entry %q must be name:32hex", e)
		}
		c.MTUsers[e[:i]] = strings.ToLower(e[i+1:])
	}
	for _, e := range splitList(os.Getenv("VPN_USERS")) {
		i := strings.Index(e, ":")
		if i <= 0 {
			return nil, fmt.Errorf("VPN_USERS entry %q must be name:secret", e)
		}
		c.VPNUsers[e[:i]] = e[i+1:]
	}
	c.ChatAllowedPhones = splitList(os.Getenv("CHAT_ALLOWED_PHONES"))

	// Optional YAML file (merged on top of env).
	if b, err := os.ReadFile(c.UsersFile); err == nil {
		var uf usersFile
		if err := yaml.Unmarshal(b, &uf); err != nil {
			return nil, fmt.Errorf("parse %s: %w", c.UsersFile, err)
		}
		c.SSTPUsers = append(c.SSTPUsers, uf.SSTPUsers...)
		for k, v := range uf.MTProxyUsers {
			c.MTUsers[k] = strings.ToLower(strings.TrimSpace(v))
		}
		c.ChatAllowedPhones = append(c.ChatAllowedPhones, uf.ChatAllowedPhones...)
	}

	for _, u := range c.SSTPUsers {
		if !userName.MatchString(u.User) || u.Password == "" || strings.ContainsAny(u.Password, "\n\r") {
			return nil, fmt.Errorf("invalid SSTP user %q", u.User)
		}
	}
	for k, v := range c.MTUsers {
		if !userName.MatchString(k) {
			return nil, fmt.Errorf("invalid MTProxy user name %q", k)
		}
		if !hex32.MatchString(v) {
			return nil, fmt.Errorf("MTProxy user %q: secret must be exactly 32 hex chars", k)
		}
	}
	// refuse to run with the placeholder values from .env.example
	if c.ChatEnable && c.ChatSecret == "change-me-to-a-long-random-secret" {
		return nil, fmt.Errorf("CHAT_SECRET still has the example value - set your own secret in .env")
	}
	for _, u := range c.SSTPUsers {
		if strings.HasPrefix(u.Password, "CHANGE_ME") {
			return nil, fmt.Errorf("SSTP user %q still has the example password - change it in .env / users.yml", u.User)
		}
	}
	for k, v := range c.MTUsers {
		if v == "00112233445566778899aabbccddeeff" {
			return nil, fmt.Errorf("MTProxy user %q uses the example secret - generate one: openssl rand -hex 16", k)
		}
	}
	if c.Domain == "vpn.example.com" {
		return nil, fmt.Errorf("DOMAIN still has the example value - set your domain in .env")
	}
	if c.VPNEnable && c.VPNSecret != "" { // empty is allowed: users may come only from VPN_USERS / files
		if c.VPNSecret == "change-me-to-a-long-random-vpn-secret" {
			return nil, fmt.Errorf("VPN_SECRET still has the example value - set your own secret in .env")
		}
		if len(c.VPNSecret) < 16 {
			return nil, fmt.Errorf("VPN_SECRET must be at least 16 characters (or leave it empty and use ./sgctl vpn add)")
		}
	}
	if c.ChatEnable && len(c.ChatSecret) < 16 {
		return nil, fmt.Errorf("CHAT_SECRET must be at least 16 characters (or set CHAT_ENABLE=false)")
	}
	if c.TURNSecret == "" {
		c.TURNSecret = "turn-" + c.ChatSecret
	}
	if c.TURNMinPort <= 0 || c.TURNMaxPort < c.TURNMinPort {
		return nil, fmt.Errorf("invalid TURN port range")
	}
	ports := map[int]string{c.HTTPSPort: "HTTPS_PORT", 80: "HTTP_PORT", c.MTInternalPort: "MTPROXY_INTERNAL_PORT"}
	for _, a := range c.MTAlt {
		if !a.Active() {
			continue
		}
		for _, p := range []int{a.Port, a.InternalPort} {
			if prev, dup := ports[p]; dup {
				return nil, fmt.Errorf("%s: port %d is already used by %s", a.Name, p, prev)
			}
			ports[p] = a.Name
		}
	}
	return c, nil
}

// Active reports whether the alternative implementation is switched on.
func (a MTAltImpl) Active() bool { return a.Enable && a.Port > 0 && a.InternalPort > 0 }

// ProxyTCPPorts: TCP ports a proxy instance relays to the main server (besides 80).
func (c *Config) ProxyTCPPorts() []int {
	out := []int{c.HTTPSPort}
	for _, a := range c.MTAlt {
		if a.Enable && a.Port > 0 && a.Port != c.HTTPSPort && a.Port != 80 {
			out = append(out, a.Port)
		}
	}
	return out
}

// MTSecretBytes returns decoded MTProxy secrets.
func (c *Config) MTSecretBytes() map[string][]byte {
	out := make(map[string][]byte, len(c.MTUsers))
	for k, v := range c.MTUsers {
		b, _ := hex.DecodeString(v)
		out[k] = b
	}
	return out
}
