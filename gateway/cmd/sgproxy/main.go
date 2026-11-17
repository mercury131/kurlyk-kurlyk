// sgproxy: slim build of securegate proxy mode (PROXY=true) for small servers (512 MB RAM).
// Same relay code as `gateway` in proxy mode, without SSTP/MTProxy/chat/TURN, so it compiles
// quickly with little memory and runs in a tiny image (see Dockerfile.proxy,
// docker-compose.proxy.yml).
package main

import (
	"fmt"
	"log/slog"
	"os"

	"securegate/internal/admin"
	"securegate/internal/config"
	"securegate/internal/proxy"
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
	_ = os.Setenv("PROXY", "true") // this binary only implements proxy mode
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
	proxy.Run(cfg)
}
