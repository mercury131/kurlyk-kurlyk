//go:build linux

// sgvpn-client is a minimal Linux client for the masquerading VPN (internal/maskvpn), used to test
// the tunnel end to end with the REAL kernel TCP/UDP stack (like a phone does), not hand-made ICMP.
// It brings up a TUN with the address the server assigns, routes everything through it (keeping a
// host route to the server) and optionally runs checks: DNS over the tunnel, HTTPS GET, download.
//
// Run in a throw-away container (it changes the routing table of its network namespace):
//
//	docker run --rm --cap-add NET_ADMIN --device /dev/net/tun -v $PWD/sgvpn-client:/c securegate-gateway:local \
//	  /c -server 1.2.3.4:443 -sni proxy.example.com -secret XXXX -insecure -check
package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/sys/unix"

	"securegate/internal/maskvpn"
)

type tcfg struct {
	Address string   `json:"address"`
	Gateway string   `json:"gateway"`
	DNS     []string `json:"dns"`
	MTU     int      `json:"mtu"`
	Routes  []string `json:"routes"`
}

func main() {
	server := flag.String("server", "", "host:port of the server or proxy")
	sni := flag.String("sni", "", "TLS SNI / Host (domain)")
	secret := flag.String("secret", "", "user secret")
	insecure := flag.Bool("insecure", false, "accept self-signed certificates")
	check := flag.Bool("check", false, "run DNS/HTTPS checks through the tunnel and exit")
	dl := flag.String("download", "", "URL to download through the tunnel during -check (throughput)")
	flag.Parse()
	if *server == "" || *sni == "" || *secret == "" {
		flag.Usage()
		os.Exit(2)
	}

	raw, err := net.DialTimeout("tcp", *server, 10*time.Second)
	check1(err, "dial")
	tc := tls.Client(raw, &tls.Config{ServerName: *sni, InsecureSkipVerify: *insecure, NextProtos: []string{"http/1.1"}})
	check1(tc.Handshake(), "tls")

	key := make([]byte, 16)
	_, _ = rand.Read(key)
	req := "GET " + maskvpn.Path + " HTTP/1.1\r\nHost: " + *sni + "\r\nUser-Agent: Mozilla/5.0\r\n" +
		"Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
		"Sec-WebSocket-Key: " + base64.StdEncoding.EncodeToString(key) + "\r\n" +
		"Cookie: __Host-sess=" + maskvpn.MakeProof(maskvpn.AuthKey(*secret), time.Now().Unix()) + "\r\n\r\n"
	_, err = tc.Write([]byte(req))
	check1(err, "write upgrade")
	br := bufio.NewReader(tc)
	status, err := br.ReadString('\n')
	check1(err, "read status")
	for {
		l, err := br.ReadString('\n')
		check1(err, "read headers")
		if l == "\r\n" {
			break
		}
	}
	if !strings.HasPrefix(status, "HTTP/1.1 101") {
		log.Fatalf("upgrade refused: %q", strings.TrimSpace(status))
	}
	cRand := make([]byte, 32)
	_, _ = rand.Read(cRand)
	_, err = tc.Write(cRand)
	check1(err, "write cRand")
	sRand := make([]byte, 32)
	_, err = io.ReadFull(br, sRand)
	check1(err, "read sRand")
	h := hkdf.New(sha256.New, maskvpn.TunKey(*secret), append(append([]byte{}, cRand...), sRand...), []byte("securegate-maskvpn-v1"))
	km := make([]byte, 64)
	_, _ = io.ReadFull(h, km)
	tx, _ := chacha20poly1305.New(km[:32])
	rx, _ := chacha20poly1305.New(km[32:])

	var rn, tn uint64
	readFrame := func() ([]byte, error) {
		hdr := make([]byte, 2)
		if _, err := io.ReadFull(br, hdr); err != nil {
			return nil, err
		}
		ct := make([]byte, binary.BigEndian.Uint16(hdr))
		if _, err := io.ReadFull(br, ct); err != nil {
			return nil, err
		}
		pt, err := rx.Open(nil, nonce(rn), ct, nil)
		rn++
		return pt, err
	}
	var wmu sync.Mutex
	writeFrame := func(pt []byte) error {
		wmu.Lock()
		defer wmu.Unlock()
		ct := tx.Seal(nil, nonce(tn), pt, nil)
		tn++
		f := make([]byte, 2+len(ct))
		binary.BigEndian.PutUint16(f, uint16(len(ct)))
		copy(f[2:], ct)
		_, err := tc.Write(f)
		return err
	}

	pt, err := readFrame()
	check1(err, "read config")
	if len(pt) == 0 || pt[0] != 0x01 {
		log.Fatalf("expected config frame")
	}
	var cfg tcfg
	check1(json.Unmarshal(pt[1:], &cfg), "config json")
	log.Printf("tunnel up: %+v", cfg)

	tun := openTUN("sgc0")
	srvIP := raw.RemoteAddr().(*net.TCPAddr).IP.String()
	gwOut, _ := exec.Command("sh", "-c", "ip route show default | awk '{print $3; exit}'").Output()
	gw := strings.TrimSpace(string(gwOut))
	run("ip", "addr", "add", cfg.Address, "dev", "sgc0")
	run("ip", "link", "set", "sgc0", "mtu", fmt.Sprint(cfg.MTU), "up")
	run("ip", "route", "replace", srvIP+"/32", "via", gw)
	run("ip", "route", "replace", "default", "dev", "sgc0")

	var stats struct{ up, down, upB, downB int64 }
	var smu sync.Mutex
	go func() { // device -> server
		buf := make([]byte, 65535)
		for {
			n, err := tun.Read(buf)
			if err != nil {
				log.Fatalf("tun read: %v", err)
			}
			smu.Lock()
			stats.up++
			stats.upB += int64(n)
			smu.Unlock()
			if err := writeFrame(append([]byte{0x02}, buf[:n]...)); err != nil {
				log.Fatalf("tunnel write: %v", err)
			}
		}
	}()
	go func() {
		for range time.Tick(20 * time.Second) {
			_ = writeFrame([]byte{0x03})
		}
	}()
	go func() { // server -> device
		for {
			pt, err := readFrame()
			if err != nil {
				log.Fatalf("tunnel read: %v", err)
			}
			if len(pt) > 1 && pt[0] == 0x02 {
				smu.Lock()
				stats.down++
				stats.downB += int64(len(pt) - 1)
				smu.Unlock()
				if _, err := tun.Write(pt[1:]); err != nil {
					log.Printf("tun write: %v", err)
				}
			}
		}
	}()

	if !*check {
		select {}
	}
	ok := true
	dns := "8.8.8.8"
	if len(cfg.DNS) > 0 {
		dns = cfg.DNS[0]
	}
	res := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "udp", dns+":53")
	}}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	addrs, err := res.LookupIPAddr(ctx, "example.com")
	cancel()
	if err != nil {
		ok = false
		log.Printf("CHECK DNS (udp %s via tunnel): FAIL %v", dns, err)
	} else {
		log.Printf("CHECK DNS (udp %s via tunnel): OK %v", dns, addrs)
	}
	hc := &http.Client{Timeout: 20 * time.Second, Transport: &http.Transport{
		DialContext: (&net.Dialer{Resolver: res}).DialContext, ForceAttemptHTTP2: true}}
	for _, u := range []string{"https://example.com/", "https://api.ipify.org/"} {
		resp, err := hc.Get(u)
		if err != nil {
			ok = false
			log.Printf("CHECK GET %s: FAIL %v", u, err)
			continue
		}
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()
		extra := ""
		if strings.Contains(u, "ipify") {
			extra = " exit-ip=" + strings.TrimSpace(string(b))
		}
		log.Printf("CHECK GET %s: OK %d, %d bytes%s", u, resp.StatusCode, len(b), extra)
	}
	if *dl != "" {
		t0 := time.Now()
		resp, err := hc.Get(*dl)
		if err != nil {
			ok = false
			log.Printf("CHECK download: FAIL %v", err)
		} else {
			n, err := io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			d := time.Since(t0).Seconds()
			if err != nil {
				ok = false
			}
			log.Printf("CHECK download: %d bytes in %.1fs (%.1f Mbit/s) err=%v", n, d, float64(n)*8/d/1e6, err)
		}
	}
	smu.Lock()
	log.Printf("stats: up %d pkts/%d B, down %d pkts/%d B", stats.up, stats.upB, stats.down, stats.downB)
	smu.Unlock()
	if !ok {
		os.Exit(1)
	}
	log.Printf("ALL CHECKS PASSED")
}

func nonce(c uint64) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint64(n[4:], c)
	return n
}

func check1(err error, what string) {
	if err != nil {
		log.Fatalf("%s: %v", what, err)
	}
}

func run(args ...string) {
	if out, err := exec.Command(args[0], args[1:]...).CombinedOutput(); err != nil {
		log.Fatalf("%v: %v %s", args, err, out)
	}
}

func openTUN(name string) *os.File {
	fd, err := unix.Open("/dev/net/tun", unix.O_RDWR, 0)
	check1(err, "open /dev/net/tun")
	req, err := unix.NewIfreq(name)
	check1(err, "ifreq")
	req.SetUint16(unix.IFF_TUN | unix.IFF_NO_PI)
	check1(unix.IoctlIfreq(fd, unix.TUNSETIFF, req), "TUNSETIFF")
	return os.NewFile(uintptr(fd), name)
}

