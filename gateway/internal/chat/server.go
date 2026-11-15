// Package chat is the transport-only messenger backend for the Android client:
// REST API under /chat/api/ and a WebSocket at /chat/ws. Message payloads are encrypted
// end-to-end by clients (AES-256-GCM with a key derived from the shared secret);
// the server only relays and temporarily queues ciphertext for offline recipients.
package chat

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"securegate/internal/turnsrv"
)

const Version = "1.0"

type Config struct {
	Domain        string
	Secret        string
	MaxUsers      int
	AllowedPhones []string
	MaxFileBytes  int64
	FilesMaxBytes int64
	FileTTL       time.Duration
	QueueTTL      time.Duration
	DataDir       string
	TURNEnabled   bool
	TURNSecret    string
	TURNUDPPort   int
	HTTPSPort     int
}

type StrikeFunc func(ip string, points int, reason string)

type Server struct {
	cfg     Config
	store   *Store
	hub     *Hub
	strike  StrikeFunc
	authKey []byte
	allowed map[string]bool
	nonceMu sync.Mutex
	nonces  map[string]time.Time
	regMu   sync.Mutex
	upMu    sync.Mutex
}

var phoneRe = regexp.MustCompile(`^\+[1-9][0-9]{6,14}$`)
var idRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// NormalizePhone converts user input to E.164 (+digits).
func NormalizePhone(p string) (string, bool) {
	var b strings.Builder
	for i, r := range strings.TrimSpace(p) {
		switch {
		case r >= '0' && r <= '9':
			b.WriteRune(r)
		case r == '+' && i == 0:
			b.WriteRune(r)
		case r == ' ' || r == '-' || r == '(' || r == ')' || r == '.':
		default:
			return "", false
		}
	}
	s := b.String()
	if !strings.HasPrefix(s, "+") {
		s = "+" + s
	}
	return s, phoneRe.MatchString(s)
}

// DeriveKey implements the key derivation shared with clients: HMAC-SHA256(secret, label).
func DeriveKey(secret, label string) []byte {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(label))
	return m.Sum(nil)
}

func New(cfg Config, strike StrikeFunc) (*Server, error) {
	if err := os.MkdirAll(filepath.Join(cfg.DataDir, "avatars"), 0o700); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Join(cfg.DataDir, "files"), 0o700); err != nil {
		return nil, err
	}
	st, err := OpenStore(filepath.Join(cfg.DataDir, "chat.db"))
	if err != nil {
		return nil, err
	}
	s := &Server{
		cfg:     cfg,
		store:   st,
		strike:  strike,
		authKey: DeriveKey(cfg.Secret, "securegate-chat-auth-v1"),
		allowed: map[string]bool{},
		nonces:  map[string]time.Time{},
	}
	for _, p := range cfg.AllowedPhones {
		if n, ok := NormalizePhone(p); ok {
			s.allowed[n] = true
		}
	}
	s.hub = newHub(s)
	go s.janitor()
	slog.Info("chat: started", "users", st.UserCount(), "max_users", cfg.MaxUsers, "whitelist", len(s.allowed))
	return s, nil
}

func (s *Server) Close() { s.store.Close() }

func (s *Server) janitor() {
	t := time.NewTicker(10 * time.Minute)
	for range t.C {
		for _, id := range s.store.Cleanup() {
			_ = os.Remove(s.filePath(id))
		}
		s.nonceMu.Lock()
		for k, v := range s.nonces {
			if time.Since(v) > 15*time.Minute {
				delete(s.nonces, k)
			}
		}
		s.nonceMu.Unlock()
	}
}

func clientIP(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func apiErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func randID(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// ServeHTTP routes /chat/... requests.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p := strings.TrimPrefix(r.URL.Path, "/chat")
	switch {
	case p == "/ws":
		s.hub.serveWS(w, r)
	case p == "/api/auth" && r.Method == http.MethodPost:
		s.handleAuth(w, r)
	case strings.HasPrefix(p, "/api/"):
		phone, ok := s.authenticate(r)
		if !ok {
			s.strike(clientIP(r), 1, "chat invalid token")
			apiErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		s.routeAPI(w, r, strings.TrimPrefix(p, "/api"), phone)
	default:
		apiErr(w, http.StatusNotFound, "not found")
	}
}

func (s *Server) authenticate(r *http.Request) (string, bool) {
	tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if tok == "" || len(tok) > 128 {
		return "", false
	}
	return s.store.TokenPhone(tok)
}

func (s *Server) routeAPI(w http.ResponseWriter, r *http.Request, p, phone string) {
	switch {
	case p == "/me/avatar" && (r.Method == http.MethodPut || r.Method == http.MethodPost):
		s.handleSetAvatar(w, r, phone)
	case p == "/me/avatar" && r.Method == http.MethodDelete:
		s.handleDeleteAvatar(w, phone)
	case strings.HasPrefix(p, "/avatars/") && (r.Method == http.MethodGet || r.Method == http.MethodHead):
		s.handleGetAvatar(w, r, strings.TrimPrefix(p, "/avatars/"))
	case p == "/me" && r.Method == http.MethodGet:
		u, _ := s.store.User(phone)
		writeJSON(w, 200, s.userView(*u))
	case p == "/me" && (r.Method == http.MethodPut || r.Method == http.MethodPatch):
		s.handleUpdateMe(w, r, phone)
	case p == "/logout" && r.Method == http.MethodPost:
		s.store.DeleteToken(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		writeJSON(w, 200, map[string]bool{"ok": true})
	case p == "/users" && r.Method == http.MethodGet:
		users := s.store.Users()
		out := make([]map[string]any, 0, len(users))
		for _, u := range users {
			out = append(out, s.userView(u))
		}
		writeJSON(w, 200, map[string]any{"users": out})
	case p == "/config" && r.Method == http.MethodGet:
		writeJSON(w, 200, map[string]any{
			"version":      Version,
			"server_time":  time.Now().Unix(),
			"max_file_mb":  s.cfg.MaxFileBytes >> 20,
			"file_ttl_h":   int(s.cfg.FileTTL.Hours()),
			"queue_ttl_h":  int(s.cfg.QueueTTL.Hours()),
			"max_users":    s.cfg.MaxUsers,
			"ice_servers":  s.iceServers(phone),
			"ws_url":       fmt.Sprintf("wss://%s%s/chat/ws", s.cfg.Domain, portSuffix(s.cfg.HTTPSPort)),
		})
	case p == "/turn" && r.Method == http.MethodGet:
		writeJSON(w, 200, map[string]any{"ice_servers": s.iceServers(phone), "ttl": 86400})
	case p == "/groups" && r.Method == http.MethodGet:
		gs := s.store.GroupsOf(phone)
		if gs == nil {
			gs = []Group{}
		}
		writeJSON(w, 200, map[string]any{"groups": gs})
	case p == "/groups" && r.Method == http.MethodPost:
		s.handleCreateGroup(w, r, phone)
	case strings.HasPrefix(p, "/groups/"):
		s.handleGroup(w, r, phone, strings.TrimPrefix(p, "/groups/"))
	case p == "/files" && r.Method == http.MethodPost:
		s.handleUpload(w, r, phone)
	case strings.HasPrefix(p, "/files/"):
		s.handleFile(w, r, phone, strings.TrimPrefix(p, "/files/"))
	default:
		apiErr(w, http.StatusNotFound, "not found")
	}
}

func portSuffix(p int) string {
	if p == 443 {
		return ""
	}
	return ":" + strconv.Itoa(p)
}

func (s *Server) userView(u User) map[string]any {
	online := s.hub.online(u.Phone)
	ls := u.LastSeen.Unix()
	if online {
		ls = time.Now().Unix()
	}
	return map[string]any{"phone": u.Phone, "name": u.Name, "online": online, "last_seen": ls, "avatar_ver": u.AvatarVer}
}

func (s *Server) iceServers(phone string) []map[string]any {
	host := s.cfg.Domain
	list := []map[string]any{}
	if !s.cfg.TURNEnabled {
		return list
	}
	user, pass := turnsrv.Credentials(s.cfg.TURNSecret, phone, 24*time.Hour)
	list = append(list, map[string]any{"urls": []string{fmt.Sprintf("stun:%s:%d", host, s.cfg.TURNUDPPort)}})
	list = append(list, map[string]any{
		"urls": []string{
			fmt.Sprintf("turn:%s:%d?transport=udp", host, s.cfg.TURNUDPPort),
			fmt.Sprintf("turns:%s:%d?transport=tcp", host, s.cfg.HTTPSPort),
		},
		"username":   user,
		"credential": pass,
	})
	return list
}

type authReq struct {
	Phone string `json:"phone"`
	Name  string `json:"name"`
	TS    int64  `json:"ts"`
	Nonce string `json:"nonce"`
	Sig   string `json:"sig"`
}

func (s *Server) handleAuth(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	var req authReq
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
		s.strike(ip, 1, "chat auth malformed")
		apiErr(w, 400, "bad request")
		return
	}
	phone, ok := NormalizePhone(req.Phone)
	if !ok {
		apiErr(w, 400, "invalid phone, use international format +79991234567")
		return
	}
	if d := time.Now().Unix() - req.TS; d > 300 || d < -300 {
		apiErr(w, 400, "clock skew too large, check device time")
		return
	}
	if len(req.Nonce) < 16 || len(req.Nonce) > 64 {
		apiErr(w, 400, "bad nonce")
		return
	}
	msg := fmt.Sprintf("v1|%s|%d|%s", phone, req.TS, req.Nonce)
	m := hmac.New(sha256.New, s.authKey)
	m.Write([]byte(msg))
	want := hex.EncodeToString(m.Sum(nil))
	if !hmac.Equal([]byte(want), []byte(strings.ToLower(req.Sig))) {
		s.strike(ip, 1, "chat auth bad signature")
		slog.Info("chat: auth failed (wrong secret)", "ip", ip, "phone", phone)
		apiErr(w, 401, "invalid secret key")
		return
	}
	s.nonceMu.Lock()
	if _, used := s.nonces[req.Nonce]; used {
		s.nonceMu.Unlock()
		apiErr(w, 401, "replayed request")
		return
	}
	s.nonces[req.Nonce] = time.Now()
	s.nonceMu.Unlock()

	if len(s.allowed) > 0 && !s.allowed[phone] {
		s.strike(ip, 1, "chat phone not allowed")
		apiErr(w, 403, "this phone number is not allowed on this server")
		return
	}
	name := cleanName(req.Name)
	s.regMu.Lock()
	u, exists := s.store.User(phone)
	isNew := false
	if !exists {
		if s.store.UserCount() >= s.cfg.MaxUsers {
			s.regMu.Unlock()
			apiErr(w, 403, "server is full")
			return
		}
		if name == "" {
			name = phone
		}
		u = &User{Phone: phone, Name: name, CreatedAt: time.Now(), LastSeen: time.Now()}
		isNew = true
	} else if name != "" && name != u.Name {
		u.Name = name
		isNew = true // broadcast update
	}
	if err := s.store.PutUser(u); err != nil {
		s.regMu.Unlock()
		apiErr(w, 500, "storage error")
		return
	}
	s.regMu.Unlock()
	tok := randID(32)
	exp := time.Now().Add(90 * 24 * time.Hour)
	if err := s.store.PutToken(tok, phone, exp); err != nil {
		apiErr(w, 500, "storage error")
		return
	}
	if isNew {
		s.hub.broadcastUser(*u)
	}
	slog.Info("chat: user authenticated", "phone", phone, "new", !exists, "ip", ip)
	writeJSON(w, 200, map[string]any{"token": tok, "expires": exp.Unix(), "me": s.userView(*u)})
}

func cleanName(n string) string {
	n = strings.TrimSpace(n)
	n = strings.Map(func(r rune) rune {
		if r < 0x20 {
			return -1
		}
		return r
	}, n)
	if utf8.RuneCountInString(n) > 64 {
		n = string([]rune(n)[:64])
	}
	return n
}

func (s *Server) handleUpdateMe(w http.ResponseWriter, r *http.Request, phone string) {
	var req struct {
		Name string `json:"name"`
	}
	if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req) != nil {
		apiErr(w, 400, "bad request")
		return
	}
	name := cleanName(req.Name)
	if name == "" {
		apiErr(w, 400, "name required")
		return
	}
	u, _ := s.store.User(phone)
	u.Name = name
	_ = s.store.PutUser(u)
	s.hub.broadcastUser(*u)
	writeJSON(w, 200, s.userView(*u))
}

// ---- groups ----

func (s *Server) validMembers(list []string) ([]string, error) {
	seen := map[string]bool{}
	var out []string
	for _, p := range list {
		n, ok := NormalizePhone(p)
		if !ok {
			return nil, fmt.Errorf("invalid phone %q", p)
		}
		if _, exists := s.store.User(n); !exists {
			return nil, fmt.Errorf("unknown user %s", n)
		}
		if !seen[n] {
			seen[n] = true
			out = append(out, n)
		}
	}
	return out, nil
}

func (s *Server) handleCreateGroup(w http.ResponseWriter, r *http.Request, phone string) {
	var req struct {
		Name    string   `json:"name"`
		Members []string `json:"members"`
	}
	if json.NewDecoder(io.LimitReader(r.Body, 65536)).Decode(&req) != nil {
		apiErr(w, 400, "bad request")
		return
	}
	name := cleanName(req.Name)
	if name == "" {
		apiErr(w, 400, "name required")
		return
	}
	members, err := s.validMembers(append(req.Members, phone))
	if err != nil {
		apiErr(w, 400, err.Error())
		return
	}
	if len(s.store.GroupsOf(phone)) >= 200 {
		apiErr(w, 400, "too many groups")
		return
	}
	g := &Group{ID: "g" + randID(8), Name: name, Owner: phone, Members: members, CreatedAt: time.Now(), UpdatedAt: time.Now()}
	if err := s.store.PutGroup(g); err != nil {
		apiErr(w, 500, "storage error")
		return
	}
	s.hub.groupEvent(g, "upsert", g.Members)
	writeJSON(w, 200, g)
}

func (s *Server) handleGroup(w http.ResponseWriter, r *http.Request, phone, rest string) {
	parts := strings.Split(rest, "/")
	if !idRe.MatchString(parts[0]) {
		apiErr(w, 404, "not found")
		return
	}
	s.upMu.Lock()
	defer s.upMu.Unlock()
	g, ok := s.store.Group(parts[0])
	if !ok || !g.has(phone) {
		apiErr(w, 404, "group not found")
		return
	}
	switch {
	case len(parts) == 1 && r.Method == http.MethodGet:
		writeJSON(w, 200, g)
	case len(parts) == 1 && r.Method == http.MethodPatch:
		var req struct {
			Name   *string  `json:"name"`
			Add    []string `json:"add"`
			Remove []string `json:"remove"`
		}
		if json.NewDecoder(io.LimitReader(r.Body, 65536)).Decode(&req) != nil {
			apiErr(w, 400, "bad request")
			return
		}
		if (req.Name != nil || len(req.Remove) > 0) && g.Owner != phone {
			apiErr(w, 403, "only the group owner can rename or remove members")
			return
		}
		old := append([]string(nil), g.Members...)
		if req.Name != nil {
			if n := cleanName(*req.Name); n != "" {
				g.Name = n
			}
		}
		if len(req.Add) > 0 {
			add, err := s.validMembers(req.Add)
			if err != nil {
				apiErr(w, 400, err.Error())
				return
			}
			for _, a := range add {
				if !g.has(a) {
					g.Members = append(g.Members, a)
				}
			}
		}
		var removed []string
		if len(req.Remove) > 0 {
			rm := map[string]bool{}
			for _, p := range req.Remove {
				if n, ok := NormalizePhone(p); ok && n != g.Owner {
					rm[n] = true
				}
			}
			kept := g.Members[:0]
			for _, m := range g.Members {
				if rm[m] {
					removed = append(removed, m)
				} else {
					kept = append(kept, m)
				}
			}
			g.Members = kept
		}
		g.UpdatedAt = time.Now()
		_ = s.store.PutGroup(g)
		s.hub.groupEvent(g, "upsert", g.Members)
		if len(removed) > 0 {
			s.hub.groupEvent(g, "removed", removed)
		}
		_ = old
		writeJSON(w, 200, g)
	case len(parts) == 2 && parts[1] == "leave" && r.Method == http.MethodPost:
		kept := g.Members[:0]
		for _, m := range g.Members {
			if m != phone {
				kept = append(kept, m)
			}
		}
		g.Members = kept
		if len(g.Members) == 0 {
			_ = s.store.DeleteGroup(g.ID)
		} else {
			if g.Owner == phone {
				g.Owner = g.Members[0]
			}
			g.UpdatedAt = time.Now()
			_ = s.store.PutGroup(g)
			s.hub.groupEvent(g, "upsert", g.Members)
		}
		s.hub.groupEvent(g, "removed", []string{phone})
		writeJSON(w, 200, map[string]bool{"ok": true})
	case len(parts) == 1 && r.Method == http.MethodDelete:
		if g.Owner != phone {
			apiErr(w, 403, "only the group owner can delete the group")
			return
		}
		_ = s.store.DeleteGroup(g.ID)
		s.hub.groupEvent(g, "removed", g.Members)
		writeJSON(w, 200, map[string]bool{"ok": true})
	default:
		apiErr(w, 405, "method not allowed")
	}
}

// ---- files ----

func (s *Server) filePath(id string) string { return filepath.Join(s.cfg.DataDir, "files", id) }

var fileIDRe = regexp.MustCompile(`^f[0-9a-f]{32}$`)

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request, phone string) {
	if r.ContentLength > s.cfg.MaxFileBytes {
		apiErr(w, 413, "file too large")
		return
	}
	if s.store.FilesTotal() > s.cfg.FilesMaxBytes {
		apiErr(w, 507, "server file storage is full, try later")
		return
	}
	id := "f" + randID(16)
	tmp := s.filePath(id) + ".part"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o600)
	if err != nil {
		apiErr(w, 500, "storage error")
		return
	}
	n, err := io.Copy(f, io.LimitReader(r.Body, s.cfg.MaxFileBytes+1))
	f.Close()
	if err != nil || n == 0 || n > s.cfg.MaxFileBytes {
		os.Remove(tmp)
		if n > s.cfg.MaxFileBytes {
			apiErr(w, 413, "file too large")
		} else {
			apiErr(w, 400, "upload failed")
		}
		return
	}
	if err := os.Rename(tmp, s.filePath(id)); err != nil {
		os.Remove(tmp)
		apiErr(w, 500, "storage error")
		return
	}
	m := &FileMeta{ID: id, Owner: phone, Size: n, Expires: time.Now().Add(s.cfg.FileTTL)}
	_ = s.store.PutFile(m)
	writeJSON(w, 200, map[string]any{"id": id, "size": n, "expires": m.Expires.Unix()})
}

func (s *Server) handleFile(w http.ResponseWriter, r *http.Request, phone, id string) {
	if !fileIDRe.MatchString(id) {
		apiErr(w, 404, "not found")
		return
	}
	m, ok := s.store.File(id)
	if !ok || time.Now().After(m.Expires) {
		apiErr(w, 404, "file expired or not found")
		return
	}
	switch r.Method {
	case http.MethodGet, http.MethodHead:
		f, err := os.Open(s.filePath(id))
		if err != nil {
			apiErr(w, 404, "not found")
			return
		}
		defer f.Close()
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Cache-Control", "no-store")
		http.ServeContent(w, r, id, time.Time{}, f)
	case http.MethodDelete:
		if m.Owner != phone {
			apiErr(w, 403, "forbidden")
			return
		}
		s.store.DeleteFile(id)
		_ = os.Remove(s.filePath(id))
		writeJSON(w, 200, map[string]bool{"ok": true})
	default:
		apiErr(w, 405, "method not allowed")
	}
}

var _ = errors.New
