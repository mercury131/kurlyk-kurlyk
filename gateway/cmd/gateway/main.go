// securegate gateway: single :443 entry point for SSTP VPN, MTProxy (telemt), chat server,
// TURN and a static website; :80 for ACME + redirect.
package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"

	"securegate/internal/admin"
	"securegate/internal/certs"
	"securegate/internal/chat"
	"securegate/internal/config"
	"securegate/internal/frontend"
	"securegate/internal/guard"
	"securegate/internal/mtproxy"
	"securegate/internal/mux"
	"securegate/internal/netx"
	"securegate/internal/proxy"
	"securegate/internal/sstp"
	"securegate/internal/turnsrv"
	"securegate/internal/web"
)

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "healthcheck":
			os.Exit(admin.Healthcheck())
		case "bans", "unban":
			os.Exit(admin.Bans(os.Args[1:]))
		}
	}
	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "config error:", err)
		os.Exit(2)
	}
	level := slog.LevelInfo
	switch cfg.LogLevel {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: level})))
	if cfg.ProxyMode {
		proxy.Run(cfg)
		return
	}
	slog.Info("securegate starting", "domain", cfg.Domain, "tls_mode", cfg.TLSMode,
		"sstp", cfg.SSTPEnable, "mtproxy", cfg.MTEnable, "chat", cfg.ChatEnable, "turn", cfg.TURNEnable)
	_ = os.MkdirAll(cfg.DataDir, 0o700)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	fe := frontend.New(cfg.ProxyFrontends, cfg.ProxyTrusted)
	if fe.Enabled() {
		slog.Info("frontend: proxy instances relay to this server", "domains", cfg.ProxyFrontends, "trusted_ips", len(cfg.ProxyTrusted))
		go fe.Run(ctx)
	}

	g := guard.New(guard.Config{
		MaxConnPerIP: cfg.GuardMaxConnPerIP,
		RatePerMin:   cfg.GuardRatePerMin,
		Strikes:      cfg.GuardStrikes,
		BanBase:      cfg.GuardBan,
		Whitelist:    cfg.GuardWhitelist,
		Exempt:       fe.Trusted, // a proxy instance is never banned (its clients are, by real IP)
		NFT:          cfg.GuardNFT,
		NFTPorts:     []int{80, cfg.HTTPSPort, cfg.TURNUDPPort},
		StateFile:    filepath.Join(cfg.DataDir, "guard", "bans.json"),
	})
	defer g.Close()

	cm, err := certs.New(cfg.Hosts(), cfg.TLSMode, cfg.ACMEEmail, cfg.DataDir, cfg.ACMEStaging, cfg.ACMEDir, cfg.SNIStrict)
	if err != nil {
		slog.Error("certs init", "err", err)
		os.Exit(1)
	}
	go cm.RunRenewal(ctx)

	// MTProxy (telemt) integration
	var mt *mtproxy.Forwarder
	if err := mtproxy.WriteTelemtConfig(cfg.MTConfigDir, cfg.MTEnable, cfg.Domain, cfg.HTTPSPort,
		cfg.MTInternalPort, cfg.HTTPSPort, cfg.MTAdTag, cfg.MTUsers); err != nil {
		slog.Error("mtproxy: cannot write telemt config", "err", err)
	}
	if cfg.MTEnable && len(cfg.MTUsers) > 0 {
		mt = mtproxy.NewForwarder(cfg.MTSecretBytes(), cfg.MTInternalPort)
		var sb strings.Builder
		for _, host := range cfg.Hosts() { // links for DOMAIN and for every proxy instance
			links := mtproxy.Links(host, cfg.HTTPSPort, cfg.MTUsers)
			names := make([]string, 0, len(links))
			for n := range links {
				names = append(names, n)
			}
			sort.Strings(names)
			for _, n := range names {
				slog.Info("mtproxy: link", "user", n, "host", host, "url", links[n])
				fmt.Fprintf(&sb, "%s (%s): %s\n", n, host, links[n])
			}
		}
		_ = os.WriteFile(filepath.Join(cfg.DataDir, "mtproxy-links.txt"), []byte(sb.String()), 0o600)
	}

	// SSTP
	var sstpSrv *sstp.Server
	if cfg.SSTPEnable {
		users := make([][2]string, 0, len(cfg.SSTPUsers))
		for _, u := range cfg.SSTPUsers {
			users = append(users, [2]string{u.User, u.Password})
		}
		if len(users) == 0 {
			slog.Warn("sstp: no users configured (SSTP_USERS), SSTP disabled")
		} else {
			sstpSrv, err = sstp.New(sstp.Config{
				LocalIP: cfg.SSTPLocalIP, Pool: cfg.SSTPPool, DNS: cfg.SSTPDNS, MTU: cfg.SSTPMTU,
				PPPDPath: cfg.PPPDPath, Debug: cfg.SSTPPPPDebug, Users: users, NAT: cfg.SSTPNAT, EtcDir: "/etc/ppp",
			}, g.Strike)
			if err != nil {
				slog.Error("sstp init failed, SSTP disabled", "err", err)
				sstpSrv = nil
			} else {
				defer sstpSrv.Close()
				slog.Info("sstp: ready", "users", len(users), "pool", cfg.SSTPPool, "local", cfg.SSTPLocalIP)
			}
		}
	}

	// TURN
	var turnSrv *turnsrv.Server
	if cfg.TURNEnable && cfg.ChatEnable {
		pub := publicIP(cfg)
		if pub == nil {
			slog.Error("turn: cannot determine public IP, set PUBLIC_IP; TURN disabled")
		} else {
			turnSrv, err = turnsrv.New(turnsrv.Config{
				Realm: cfg.Domain, Secret: cfg.TURNSecret, PublicIP: pub,
				UDPPort: cfg.TURNUDPPort, MinPort: cfg.TURNMinPort, MaxPort: cfg.TURNMaxPort,
			})
			if err != nil {
				slog.Error("turn init failed", "err", err)
				turnSrv = nil
			} else {
				defer turnSrv.Close()
			}
		}
	}

	// Chat
	var chatHandler http.Handler
	if cfg.ChatEnable {
		cs, err := chat.New(chat.Config{
			Domain: cfg.Domain, Serves: cm.Serves, Secret: cfg.ChatSecret, MaxUsers: cfg.ChatMaxUsers,
			AllowedPhones: cfg.ChatAllowedPhones,
			MaxFileBytes:  int64(cfg.ChatMaxFileMB) << 20, FilesMaxBytes: int64(cfg.ChatFilesMaxGB) << 30,
			FileTTL: cfg.ChatFileTTL, QueueTTL: cfg.ChatQueueTTL, DataDir: filepath.Join(cfg.DataDir, "chat"),
			TURNEnabled: turnSrv != nil, TURNSecret: cfg.TURNSecret, TURNUDPPort: cfg.TURNUDPPort, HTTPSPort: cfg.HTTPSPort,
		}, g.Strike)
		if err != nil {
			slog.Error("chat init failed", "err", err)
			os.Exit(1)
		}
		defer cs.Close()
		chatHandler = cs
	}

	// HTTPS (behind mux)
	httpLn := netx.NewChanListener(&net.TCPAddr{IP: net.IPv4zero, Port: cfg.HTTPSPort})
	httpsSrv := &http.Server{
		Handler:           web.Handler(cm.Serves, chatHandler, g.Strike),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       0, // file uploads / websockets
		WriteTimeout:      0,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
		ErrorLog:          nil,
		TLSNextProto:      map[string]func(*http.Server, *tls.Conn, http.Handler){},
	}
	go func() { _ = httpsSrv.Serve(httpLn) }()

	m := &mux.Server{Serves: cm.Serves, Frontends: fe, Guard: g, TLS: cm.TLSConfig(), MT: mt, SSTP: sstpSrv, TURN: turnSrv, HTTP: httpLn}
	ln443, err := net.Listen("tcp", cfg.HTTPSAddr)
	if err != nil {
		slog.Error("listen 443", "err", err)
		os.Exit(1)
	}
	go func() { _ = m.Serve(ln443) }()

	// HTTP :80 -> ACME + redirect
	ln80, err := net.Listen("tcp", cfg.HTTPAddr)
	if err != nil {
		slog.Error("listen 80", "err", err)
		os.Exit(1)
	}
	httpSrv := &http.Server{
		Handler:           cm.HTTPHandler(web.RedirectHandler(cfg.Domain, cm.Serves, strconv.Itoa(cfg.HTTPSPort), g.Strike)),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    8 << 10,
	}
	go func() { _ = httpSrv.Serve(newGuardedListener(ln80, g, fe)) }()

	_ = os.WriteFile("/tmp/securegate.ready", []byte("ok"), 0o644)
	slog.Info("securegate ready", "https", cfg.HTTPSAddr, "http", cfg.HTTPAddr)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
	for s := range sig {
		if s == syscall.SIGHUP {
			g.Reload()
			continue
		}
		break
	}
	slog.Info("shutting down")
	cancel()
	ln443.Close()
	ln80.Close()
	sctx, scancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer scancel()
	_ = httpSrv.Shutdown(sctx)
	_ = httpsSrv.Shutdown(sctx)
}

// guardedListener feeds the port-80 http.Server: connections pass the guard (and, from proxy
// instances, the PROXY v2 header) in their own goroutine, so a slow client never blocks Accept.
type guardedListener struct {
	*netx.ChanListener
}

func newGuardedListener(ln net.Listener, g *guard.Guard, fe *frontend.Set) *guardedListener {
	cl := netx.NewChanListener(ln.Addr())
	go func() {
		defer cl.Close()
		for {
			c, err := ln.Accept()
			if err != nil {
				if netx.IsTimeout(err) {
					time.Sleep(50 * time.Millisecond)
					continue
				}
				return
			}
			go func(c net.Conn) {
				if fe.TrustedAddr(c.RemoteAddr()) {
					c2, ok := mux.AcceptProxied(c)
					if !ok {
						return
					}
					c = c2
				}
				release, ok := g.Admit(guard.IPOf(c.RemoteAddr()))
				if !ok {
					netx.Reset(c)
					return
				}
				cl.Push(&netx.ReaderConn{Conn: c, R: c, OnClose: release})
			}(c)
		}
	}()
	return &guardedListener{cl}
}

// publicIP: PUBLIC_IP env, else DNS A record of the domain, else first global interface address.
func publicIP(cfg *config.Config) net.IP {
	if cfg.PublicIP != "" {
		return net.ParseIP(cfg.PublicIP).To4()
	}
	if ips, err := net.LookupIP(cfg.Domain); err == nil {
		for _, ip := range ips {
			if v4 := ip.To4(); v4 != nil {
				return v4
			}
		}
	}
	addrs, _ := net.InterfaceAddrs()
	var private net.IP
	for _, a := range addrs {
		if n, ok := a.(*net.IPNet); ok {
			ip := n.IP.To4()
			if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
				continue
			}
			if strings.HasPrefix(ip.String(), "172.17.") || strings.HasPrefix(ip.String(), "192.168.17.") {
				continue
			}
			if !ip.IsPrivate() {
				return ip
			}
			if private == nil {
				private = ip
			}
		}
	}
	return private
}
