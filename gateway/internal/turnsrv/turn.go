// Package turnsrv runs an embedded STUN/TURN server (pion/turn) for WebRTC calls:
// UDP on TURN_UDP_PORT and TURN-over-TLS multiplexed on :443 (fed by the gateway mux).
package turnsrv

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"time"

	"github.com/pion/logging"
	"github.com/pion/turn/v4"

	"securegate/internal/netx"
)

type Config struct {
	Realm    string
	Secret   string
	PublicIP net.IP
	UDPPort  int
	MinPort  int
	MaxPort  int
}

type Server struct {
	cfg  Config
	tcp  *netx.ChanListener
	srv  *turn.Server
	udp  net.PacketConn
}

func New(cfg Config) (*Server, error) {
	udp, err := net.ListenPacket("udp4", fmt.Sprintf("0.0.0.0:%d", cfg.UDPPort))
	if err != nil {
		return nil, fmt.Errorf("turn udp listen: %w", err)
	}
	s := &Server{cfg: cfg, udp: udp, tcp: netx.NewChanListener(&net.TCPAddr{IP: net.IPv4zero, Port: 443})}
	gen := func() turn.RelayAddressGenerator {
		return &turn.RelayAddressGeneratorPortRange{
			RelayAddress: cfg.PublicIP,
			Address:      "0.0.0.0",
			MinPort:      uint16(cfg.MinPort),
			MaxPort:      uint16(cfg.MaxPort),
		}
	}
	lf := logging.NewDefaultLoggerFactory()
	lf.DefaultLogLevel = logging.LogLevelWarn
	srv, err := turn.NewServer(turn.ServerConfig{
		Realm:         cfg.Realm,
		AuthHandler:   s.auth,
		LoggerFactory: lf,
		PacketConnConfigs: []turn.PacketConnConfig{{
			PacketConn:            udp,
			RelayAddressGenerator: gen(),
			PermissionHandler:     s.permit,
		}},
		ListenerConfigs: []turn.ListenerConfig{{
			Listener:              s.tcp,
			RelayAddressGenerator: gen(),
			PermissionHandler:     s.permit,
		}},
	})
	if err != nil {
		udp.Close()
		return nil, err
	}
	s.srv = srv
	slog.Info("turn: started", "udp", cfg.UDPPort, "relay_ip", cfg.PublicIP.String(), "relay_ports", fmt.Sprintf("%d-%d", cfg.MinPort, cfg.MaxPort))
	return s, nil
}

// PushTCP hands a TLS-terminated TURN connection (from :443) to the TURN server.
func (s *Server) PushTCP(c net.Conn) { s.tcp.Push(c) }

func (s *Server) Close() {
	if s.srv != nil {
		_ = s.srv.Close()
	}
}

// Credentials returns TURN REST API style temporary credentials for a user.
func Credentials(secret, user string, ttl time.Duration) (string, string) {
	username := strconv.FormatInt(time.Now().Add(ttl).Unix(), 10) + ":" + user
	m := hmac.New(sha1.New, []byte(secret))
	m.Write([]byte(username))
	return username, base64.StdEncoding.EncodeToString(m.Sum(nil))
}

func (s *Server) auth(username, realm string, src net.Addr) ([]byte, bool) {
	i := strings.Index(username, ":")
	if i <= 0 {
		return nil, false
	}
	exp, err := strconv.ParseInt(username[:i], 10, 64)
	if err != nil || time.Now().Unix() > exp {
		return nil, false
	}
	m := hmac.New(sha1.New, []byte(s.cfg.Secret))
	m.Write([]byte(username))
	password := base64.StdEncoding.EncodeToString(m.Sum(nil))
	return turn.GenerateAuthKey(username, realm, password), true
}

// permit blocks relaying to loopback / link-local / unspecified / multicast destinations
// (prevents using TURN to reach services on the server itself).
func (s *Server) permit(_ net.Addr, peer net.IP) bool {
	if peer.IsLoopback() || peer.IsUnspecified() || peer.IsMulticast() || peer.IsLinkLocalUnicast() {
		return false
	}
	if s.cfg.PublicIP != nil && peer.Equal(s.cfg.PublicIP) {
		return true // relay-to-relay on the same server
	}
	// Private ranges are only reachable when the server itself sits in a private network (LAN tests).
	if peer.IsPrivate() && (s.cfg.PublicIP == nil || !s.cfg.PublicIP.IsPrivate()) {
		return false
	}
	return true
}
