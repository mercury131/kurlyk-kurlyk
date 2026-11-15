// Package web serves the static demo site and applies HTTP-level protection
// (anti-bot, anti-scanner, security headers).
package web

import (
	"embed"
	"io/fs"
	"net"
	"net/http"
	"regexp"
	"strings"
)

//go:embed static
var staticFS embed.FS

type StrikeFunc func(ip string, points int, reason string)

var (
	// vulnerability scanners / exploit probes
	badPath = regexp.MustCompile(`(?i)(\.php|\.asp|\.aspx|\.jsp|\.cgi|\.env|\.git|\.svn|\.hg|\.ds_store|\.bak|\.sql|\.ini|\.log|\.yml|\.yaml|\.conf|wp-|wordpress|phpmyadmin|pma|/admin|/manager|/actuator|/vendor|/cgi-bin|/boaform|/solr|/druid|/console|/hnap|/owa|/autodiscover|/remote|/api/v1|/\.well-known/security|/config|/setup|/shell|/xmlrpc|/telescope|/debug|/server-status|/login|/user|/HNAP1|\.\./|%2e%2e|/etc/passwd)`)
	// scanners and offensive tools
	badUA = regexp.MustCompile(`(?i)(masscan|zgrab|nmap|nikto|sqlmap|nuclei|httpx|gobuster|dirbuster|feroxbuster|wpscan|censys|shodan|internet-measurement|expanse|netcraft|python-requests|go-http-client|libwww|scrapy|zmap|fuzz|nessus|openvas|acunetix|whatweb|leakix|binaryedge|onyphe|modatscanner|ahrefs|semrush|mj12bot|dotbot|petalbot|bytespider|gptbot|claudebot|ccbot|dataforseo)`)
	// search engines: not banned, just refused
	searchUA = regexp.MustCompile(`(?i)(googlebot|bingbot|yandex|baiduspider|duckduckbot|slurp|sogou|exabot|applebot|seznambot|mail\.ru_bot|facebookexternalhit|twitterbot|telegrambot|crawler|spider|bot\b)`)
)

func ipOf(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

// Handler builds the HTTPS handler: protection -> static site / chat.
func Handler(domain string, chat http.Handler, strike StrikeFunc) http.Handler {
	sub, _ := fs.Sub(staticFS, "static")
	files := http.FileServer(http.FS(sub))

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := ipOf(r)
		h := w.Header()
		h.Set("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, noimageindex")
		h.Set("Strict-Transport-Security", "max-age=31536000")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")

		host := strings.ToLower(r.Host)
		if i := strings.LastIndex(host, ":"); i > 0 && !strings.Contains(host[i:], "]") {
			host = host[:i]
		}
		if host != domain {
			strike(ip, 2, "http wrong Host header")
			http.Error(w, "Not Found", http.StatusNotFound)
			return
		}
		path := r.URL.Path
		ua := r.UserAgent()

		if path == "/robots.txt" {
			h.Set("Content-Type", "text/plain; charset=utf-8")
			_, _ = w.Write([]byte("User-agent: *\nDisallow: /\n"))
			return
		}
		if strings.HasPrefix(path, "/chat/") && chat != nil {
			chat.ServeHTTP(w, r)
			return
		}
		if ua == "" || badUA.MatchString(ua) {
			strike(ip, 2, "http scanner user-agent")
			http.Error(w, "Forbidden", http.StatusForbidden)
			return
		}
		if searchUA.MatchString(ua) {
			http.Error(w, "Forbidden", http.StatusForbidden)
			return
		}
		if badPath.MatchString(path) || badPath.MatchString(r.URL.RawQuery) {
			strike(ip, 3, "http exploit probe "+trunc(path, 60))
			http.Error(w, "Not Found", http.StatusNotFound)
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			strike(ip, 1, "http method "+r.Method)
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}
		switch {
		case path == "/" || path == "/index.html" || path == "/favicon.svg" || strings.HasPrefix(path, "/assets/"):
			h.Set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; script-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'")
			h.Set("Cache-Control", "public, max-age=3600")
			if path == "/index.html" {
				r.URL.Path = "/"
			}
			files.ServeHTTP(w, r)
		default:
			strike(ip, 1, "http 404 "+trunc(path, 60))
			http.Error(w, "Not Found", http.StatusNotFound)
		}
	})
}

// RedirectHandler is the port-80 handler (after ACME): redirect to https://domain.
func RedirectHandler(domain string, httpsPort string, strike StrikeFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := ipOf(r)
		if badPath.MatchString(r.URL.Path) {
			strike(ip, 3, "http80 exploit probe "+trunc(r.URL.Path, 60))
			http.Error(w, "Not Found", http.StatusNotFound)
			return
		}
		if badUA.MatchString(r.UserAgent()) {
			strike(ip, 2, "http80 scanner user-agent")
			http.Error(w, "Forbidden", http.StatusForbidden)
			return
		}
		w.Header().Set("X-Robots-Tag", "noindex, nofollow")
		target := "https://" + domain
		if httpsPort != "443" {
			target += ":" + httpsPort
		}
		if r.URL.Path == "/" || r.URL.Path == "/index.html" {
			target += "/"
		} else {
			target += "/"
		}
		http.Redirect(w, r, target, http.StatusMovedPermanently)
	})
}

func trunc(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}
