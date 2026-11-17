// Package admin: container CLI commands shared by the gateway and sgproxy binaries.
package admin

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"syscall"
	"time"

	"securegate/internal/guard"
)

// Bans implements `gateway bans` and `gateway unban <ip|all>` (run via docker compose exec).
func Bans(args []string) int {
	dataDir := os.Getenv("DATA_DIR")
	if dataDir == "" {
		dataDir = "/data"
	}
	state := filepath.Join(dataDir, "guard", "bans.json")
	if args[0] == "bans" {
		now := time.Now()
		n := 0
		for ip, b := range guard.AdminList(state) {
			if b.Until.After(now) {
				fmt.Printf("%-40s until %s  (ban #%d) %s\n", ip, b.Until.Format(time.RFC3339), b.Count, b.Reason)
				n++
			}
		}
		fmt.Printf("%d active ban(s)\n", n)
		return 0
	}
	if len(args) < 2 {
		fmt.Println("usage: gateway unban <ip|all>")
		return 2
	}
	n, err := guard.AdminUnban(state, args[1])
	if err != nil {
		fmt.Println("error:", err)
		return 1
	}
	_ = syscall.Kill(1, syscall.SIGHUP) // gateway is PID 1 in the container
	fmt.Printf("removed %d ban record(s)\n", n)
	return 0
}

func Healthcheck() int {
	if _, err := os.Stat("/tmp/securegate.ready"); err != nil {
		return 1
	}
	port := os.Getenv("HTTPS_PORT")
	if port == "" {
		port = "443"
	}
	c, err := net.DialTimeout("tcp", "127.0.0.1:"+port, 3*time.Second)
	if err != nil {
		return 1
	}
	c.Close()
	return 0
}
