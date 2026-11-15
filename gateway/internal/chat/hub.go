package chat

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	maxWSMessage = 512 * 1024
	maxPayload   = 256 * 1024
	pingEvery    = 25 * time.Second
	readTimeout  = 70 * time.Second
)

type client struct {
	phone  string
	conn   *websocket.Conn
	send   chan []byte
	done   chan struct{}
	once   sync.Once
	tokens float64
	last   time.Time
}

func (c *client) close() {
	c.once.Do(func() {
		close(c.done)
		c.conn.Close()
	})
}

func (c *client) push(b []byte) bool {
	select {
	case c.send <- b:
		return true
	case <-c.done:
		return false
	default:
		slog.Warn("chat: client send buffer full, dropping connection", "phone", c.phone)
		c.close()
		return false
	}
}

type Hub struct {
	s       *Server
	mu      sync.RWMutex
	clients map[string]*client
	up      websocket.Upgrader
}

func newHub(s *Server) *Hub {
	h := &Hub{s: s, clients: map[string]*client{}}
	h.up = websocket.Upgrader{
		ReadBufferSize:  16384,
		WriteBufferSize: 16384,
		CheckOrigin: func(r *http.Request) bool {
			o := r.Header.Get("Origin")
			return o == "" || strings.EqualFold(o, "https://"+s.cfg.Domain)
		},
	}
	return h
}

func (h *Hub) online(phone string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.clients[phone]
	return ok
}

func (h *Hub) get(phone string) *client {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.clients[phone]
}

func (h *Hub) sendTo(phone string, b []byte) bool {
	if c := h.get(phone); c != nil {
		return c.push(b)
	}
	return false
}

func (h *Hub) broadcast(b []byte, except string) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for p, c := range h.clients {
		if p != except {
			c.push(b)
		}
	}
}

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

func (h *Hub) broadcastUser(u User) {
	h.broadcast(mustJSON(map[string]any{"type": "user", "user": h.s.userView(u)}), "")
}

// enqueue stores a reliable event for phone and pushes it if the recipient is online.
func (h *Hub) enqueue(phone string, ev map[string]any) {
	b, err := h.s.store.Enqueue(phone, h.s.cfg.QueueTTL, func(qid string) ([]byte, error) {
		ev["qid"] = qid
		return json.Marshal(ev)
	})
	if err != nil {
		slog.Error("chat: enqueue failed", "err", err)
		return
	}
	h.sendTo(phone, b)
}

func (h *Hub) groupEvent(g *Group, event string, to []string) {
	for _, p := range to {
		h.enqueue(p, map[string]any{"type": "group", "event": event, "group": g, "ts": time.Now().UnixMilli()})
	}
}

func (h *Hub) serveWS(w http.ResponseWriter, r *http.Request) {
	tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if tok == "" {
		tok = r.URL.Query().Get("token")
	}
	phone, ok := "", false
	if tok != "" && len(tok) <= 128 {
		phone, ok = h.s.store.TokenPhone(tok)
	}
	if !ok {
		h.s.strike(clientIP(r), 1, "chat ws unauthorized")
		apiErr(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	conn, err := h.up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	conn.SetReadLimit(maxWSMessage)
	c := &client{phone: phone, conn: conn, send: make(chan []byte, 1024), done: make(chan struct{}), tokens: 50, last: time.Now()}

	h.mu.Lock()
	old := h.clients[phone]
	h.clients[phone] = c
	h.mu.Unlock()
	if old != nil {
		old.push(mustJSON(map[string]any{"type": "error", "code": "replaced", "message": "signed in from another connection"}))
		time.AfterFunc(200*time.Millisecond, old.close)
	}
	slog.Info("chat: ws connected", "phone", phone, "ip", clientIP(r))

	go h.writer(c)
	c.push(mustJSON(map[string]any{"type": "hello", "phone": phone, "server_time": time.Now().UnixMilli()}))
	for _, ev := range h.s.store.Pending(phone, 5000) {
		if !c.push(ev) {
			break
		}
	}
	if old == nil {
		h.broadcast(mustJSON(map[string]any{"type": "presence", "phone": phone, "online": true, "last_seen": time.Now().Unix()}), phone)
	}
	h.reader(c)

	h.mu.Lock()
	still := h.clients[phone] == c
	if still {
		delete(h.clients, phone)
	}
	h.mu.Unlock()
	c.close()
	if still {
		if u, ok := h.s.store.User(phone); ok {
			u.LastSeen = time.Now()
			_ = h.s.store.PutUser(u)
		}
		h.broadcast(mustJSON(map[string]any{"type": "presence", "phone": phone, "online": false, "last_seen": time.Now().Unix()}), phone)
	}
	slog.Info("chat: ws disconnected", "phone", phone)
}

func (h *Hub) writer(c *client) {
	t := time.NewTicker(pingEvery)
	defer t.Stop()
	for {
		select {
		case b := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(20 * time.Second))
			if err := c.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				c.close()
				return
			}
		case <-t.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				c.close()
				return
			}
		case <-c.done:
			return
		}
	}
}

type inMsg struct {
	Type    string          `json:"type"`
	ID      string          `json:"id"`
	To      string          `json:"to"`
	Payload string          `json:"payload"`
	QIDs    []string        `json:"qids"`
	IDs     []string        `json:"ids"`
	CallID  string          `json:"call_id"`
	Action  string          `json:"action"`
	Data    json.RawMessage `json:"data"`
}

var callActions = map[string]bool{"offer": true, "answer": true, "ice": true, "hangup": true, "reject": true, "busy": true, "ringing": true}

func (h *Hub) reader(c *client) {
	_ = c.conn.SetReadDeadline(time.Now().Add(readTimeout))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(readTimeout))
	})
	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			return
		}
		_ = c.conn.SetReadDeadline(time.Now().Add(readTimeout))
		// simple per-connection rate limit: 30 msgs/s, burst 100
		now := time.Now()
		c.tokens += now.Sub(c.last).Seconds() * 30
		if c.tokens > 100 {
			c.tokens = 100
		}
		c.last = now
		if c.tokens < 1 {
			c.push(mustJSON(map[string]any{"type": "error", "code": "rate_limited", "message": "slow down"}))
			continue
		}
		c.tokens--
		var m inMsg
		if json.Unmarshal(data, &m) != nil {
			c.push(mustJSON(map[string]any{"type": "error", "code": "bad_json"}))
			continue
		}
		h.handle(c, &m)
	}
}

func (h *Hub) errTo(c *client, id, code, msg string) {
	c.push(mustJSON(map[string]any{"type": "error", "id": id, "code": code, "message": msg}))
}

// resolve returns recipients for "to": a phone or "group:<id>".
func (h *Hub) resolve(from, to string) (recipients []string, group string, ok bool) {
	if strings.HasPrefix(to, "group:") {
		gid := strings.TrimPrefix(to, "group:")
		g, exists := h.s.store.Group(gid)
		if !exists || !g.has(from) {
			return nil, "", false
		}
		for _, m := range g.Members {
			if m != from {
				recipients = append(recipients, m)
			}
		}
		return recipients, gid, true
	}
	p, valid := NormalizePhone(to)
	if !valid {
		return nil, "", false
	}
	if _, exists := h.s.store.User(p); !exists {
		return nil, "", false
	}
	return []string{p}, "", true
}

func (h *Hub) handle(c *client, m *inMsg) {
	now := time.Now().UnixMilli()
	switch m.Type {
	case "ping":
		c.push(mustJSON(map[string]any{"type": "pong", "server_time": now}))

	case "msg":
		if !idRe.MatchString(m.ID) || m.Payload == "" || len(m.Payload) > maxPayload {
			h.errTo(c, m.ID, "bad_message", "id (1-64 chars [A-Za-z0-9_-]) and payload (<=256KB) required")
			return
		}
		rcpt, group, ok := h.resolve(c.phone, m.To)
		if !ok {
			h.errTo(c, m.ID, "unknown_recipient", "recipient not found")
			return
		}
		for _, p := range rcpt {
			ev := map[string]any{"type": "msg", "id": m.ID, "from": c.phone, "to": m.To, "payload": m.Payload, "ts": now}
			if group != "" {
				ev["group"] = group
			}
			h.enqueue(p, ev)
		}
		c.push(mustJSON(map[string]any{"type": "sent", "id": m.ID, "ts": now}))

	case "ack":
		if len(m.QIDs) == 0 || len(m.QIDs) > 1000 {
			return
		}
		removed := h.s.store.Ack(c.phone, m.QIDs)
		// delivery receipts for acknowledged messages
		bySender := map[string][]string{}
		groupOf := map[string]string{}
		for _, raw := range removed {
			var ev struct {
				Type  string `json:"type"`
				ID    string `json:"id"`
				From  string `json:"from"`
				Group string `json:"group"`
			}
			if json.Unmarshal(raw, &ev) == nil && ev.Type == "msg" && ev.From != "" && ev.From != c.phone {
				bySender[ev.From] = append(bySender[ev.From], ev.ID)
				groupOf[ev.From] = ev.Group
			}
		}
		for sender, ids := range bySender {
			rc := map[string]any{"type": "receipt", "ids": ids, "by": c.phone, "status": "delivered", "ts": now}
			if g := groupOf[sender]; g != "" {
				rc["group"] = g
			}
			h.enqueue(sender, rc)
		}

	case "read":
		if len(m.IDs) == 0 || len(m.IDs) > 1000 {
			return
		}
		p, ok := NormalizePhone(m.To)
		if !ok {
			return
		}
		if _, exists := h.s.store.User(p); !exists {
			return
		}
		h.enqueue(p, map[string]any{"type": "receipt", "ids": m.IDs, "by": c.phone, "status": "read", "ts": now})

	case "typing":
		rcpt, group, ok := h.resolve(c.phone, m.To)
		if !ok {
			return
		}
		ev := map[string]any{"type": "typing", "from": c.phone, "ts": now}
		if group != "" {
			ev["group"] = group
		}
		b := mustJSON(ev)
		for _, p := range rcpt {
			h.sendTo(p, b)
		}

	case "call":
		if !callActions[m.Action] || !idRe.MatchString(m.CallID) || len(m.Data) > 64*1024 {
			h.errTo(c, m.CallID, "bad_call", "invalid call signal")
			return
		}
		p, ok := NormalizePhone(m.To)
		if !ok {
			return
		}
		ev := map[string]any{"type": "call", "from": c.phone, "call_id": m.CallID, "action": m.Action, "ts": now}
		if len(m.Data) > 0 {
			ev["data"] = m.Data
		}
		if !h.sendTo(p, mustJSON(ev)) && m.Action == "offer" {
			c.push(mustJSON(map[string]any{"type": "call", "from": p, "call_id": m.CallID, "action": "unavailable", "ts": now}))
		}

	default:
		h.errTo(c, m.ID, "unknown_type", "unknown message type")
	}
}
