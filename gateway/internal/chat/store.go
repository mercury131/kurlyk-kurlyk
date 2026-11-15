package chat

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"time"

	bolt "go.etcd.io/bbolt"
)

var (
	bUsers  = []byte("users")
	bTokens = []byte("tokens")
	bQueue  = []byte("queue")
	bGroups = []byte("groups")
	bFiles  = []byte("files")
)

type User struct {
	Phone     string    `json:"phone"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"created_at"`
	LastSeen  time.Time `json:"last_seen"`
	AvatarVer int64     `json:"avatar_ver,omitempty"` // unix ms of last avatar change, 0 = none
}

type Group struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Owner     string    `json:"owner"`
	Members   []string  `json:"members"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

func (g *Group) has(phone string) bool {
	for _, m := range g.Members {
		if m == phone {
			return true
		}
	}
	return false
}

type tokenRec struct {
	Phone   string    `json:"phone"`
	Expires time.Time `json:"expires"`
}

type FileMeta struct {
	ID      string    `json:"id"`
	Owner   string    `json:"owner"`
	Size    int64     `json:"size"`
	Expires time.Time `json:"expires"`
}

// queued event as stored: recipient phone + raw JSON event (with qid filled in).
type queued struct {
	Expires time.Time       `json:"exp"`
	Event   json.RawMessage `json:"ev"`
}

type Store struct {
	db *bolt.DB
}

func OpenStore(path string) (*Store, error) {
	db, err := bolt.Open(path, 0o600, &bolt.Options{Timeout: 5 * time.Second})
	if err != nil {
		return nil, err
	}
	err = db.Update(func(tx *bolt.Tx) error {
		for _, b := range [][]byte{bUsers, bTokens, bQueue, bGroups, bFiles} {
			if _, err := tx.CreateBucketIfNotExists(b); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func put(tx *bolt.Tx, bucket []byte, key string, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return tx.Bucket(bucket).Put([]byte(key), b)
}

func get(tx *bolt.Tx, bucket []byte, key string, v any) bool {
	b := tx.Bucket(bucket).Get([]byte(key))
	if b == nil {
		return false
	}
	return json.Unmarshal(b, v) == nil
}

// ---- users ----

func (s *Store) User(phone string) (*User, bool) {
	var u User
	var ok bool
	_ = s.db.View(func(tx *bolt.Tx) error { ok = get(tx, bUsers, phone, &u); return nil })
	return &u, ok
}

func (s *Store) Users() []User {
	var out []User
	_ = s.db.View(func(tx *bolt.Tx) error {
		return tx.Bucket(bUsers).ForEach(func(_, v []byte) error {
			var u User
			if json.Unmarshal(v, &u) == nil {
				out = append(out, u)
			}
			return nil
		})
	})
	return out
}

func (s *Store) UserCount() int {
	n := 0
	_ = s.db.View(func(tx *bolt.Tx) error { n = tx.Bucket(bUsers).Stats().KeyN; return nil })
	return n
}

func (s *Store) PutUser(u *User) error {
	return s.db.Update(func(tx *bolt.Tx) error { return put(tx, bUsers, u.Phone, u) })
}

// ---- tokens ----

func tokenKey(tok string) string {
	h := sha256.Sum256([]byte(tok))
	return hex.EncodeToString(h[:])
}

func (s *Store) PutToken(tok, phone string, exp time.Time) error {
	return s.db.Update(func(tx *bolt.Tx) error { return put(tx, bTokens, tokenKey(tok), tokenRec{phone, exp}) })
}

func (s *Store) TokenPhone(tok string) (string, bool) {
	var r tokenRec
	var ok bool
	_ = s.db.View(func(tx *bolt.Tx) error { ok = get(tx, bTokens, tokenKey(tok), &r); return nil })
	if !ok || time.Now().After(r.Expires) {
		return "", false
	}
	return r.Phone, true
}

func (s *Store) DeleteToken(tok string) {
	_ = s.db.Update(func(tx *bolt.Tx) error { return tx.Bucket(bTokens).Delete([]byte(tokenKey(tok))) })
}

// ---- queue ----
// Keys: phone + 0x00 + 8-byte big-endian sequence. qid = hex(sequence).

func qkey(phone string, seq uint64) []byte {
	k := make([]byte, len(phone)+9)
	copy(k, phone)
	binary.BigEndian.PutUint64(k[len(phone)+1:], seq)
	return k
}

// Enqueue stores an event for phone. build receives the qid and must return the JSON event.
func (s *Store) Enqueue(phone string, ttl time.Duration, build func(qid string) ([]byte, error)) ([]byte, error) {
	var out []byte
	err := s.db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket(bQueue)
		seq, err := b.NextSequence()
		if err != nil {
			return err
		}
		qid := hex.EncodeToString(binary.BigEndian.AppendUint64(nil, seq))
		ev, err := build(qid)
		if err != nil {
			return err
		}
		out = ev
		v, _ := json.Marshal(queued{Expires: time.Now().Add(ttl), Event: ev})
		return b.Put(qkey(phone, seq), v)
	})
	return out, err
}

// Pending returns queued events for phone in order.
func (s *Store) Pending(phone string, limit int) [][]byte {
	var out [][]byte
	prefix := append([]byte(phone), 0)
	_ = s.db.View(func(tx *bolt.Tx) error {
		c := tx.Bucket(bQueue).Cursor()
		for k, v := c.Seek(prefix); k != nil && len(k) == len(prefix)+8 && string(k[:len(prefix)]) == string(prefix); k, v = c.Next() {
			var q queued
			if json.Unmarshal(v, &q) == nil && time.Now().Before(q.Expires) {
				out = append(out, append([]byte(nil), q.Event...))
				if len(out) >= limit {
					break
				}
			}
		}
		return nil
	})
	return out
}

// Ack removes events and returns the removed raw events.
func (s *Store) Ack(phone string, qids []string) [][]byte {
	var removed [][]byte
	_ = s.db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket(bQueue)
		for _, q := range qids {
			raw, err := hex.DecodeString(q)
			if err != nil || len(raw) != 8 {
				continue
			}
			k := qkey(phone, binary.BigEndian.Uint64(raw))
			if v := b.Get(k); v != nil {
				var qq queued
				if json.Unmarshal(v, &qq) == nil {
					removed = append(removed, append([]byte(nil), qq.Event...))
				}
				_ = b.Delete(k)
			}
		}
		return nil
	})
	return removed
}

// ---- groups ----

func (s *Store) Group(id string) (*Group, bool) {
	var g Group
	var ok bool
	_ = s.db.View(func(tx *bolt.Tx) error { ok = get(tx, bGroups, id, &g); return nil })
	return &g, ok
}

func (s *Store) PutGroup(g *Group) error {
	return s.db.Update(func(tx *bolt.Tx) error { return put(tx, bGroups, g.ID, g) })
}

func (s *Store) DeleteGroup(id string) error {
	return s.db.Update(func(tx *bolt.Tx) error { return tx.Bucket(bGroups).Delete([]byte(id)) })
}

func (s *Store) GroupsOf(phone string) []Group {
	var out []Group
	_ = s.db.View(func(tx *bolt.Tx) error {
		return tx.Bucket(bGroups).ForEach(func(_, v []byte) error {
			var g Group
			if json.Unmarshal(v, &g) == nil && g.has(phone) {
				out = append(out, g)
			}
			return nil
		})
	})
	return out
}

// ---- files ----

func (s *Store) PutFile(m *FileMeta) error {
	return s.db.Update(func(tx *bolt.Tx) error { return put(tx, bFiles, m.ID, m) })
}

func (s *Store) File(id string) (*FileMeta, bool) {
	var m FileMeta
	var ok bool
	_ = s.db.View(func(tx *bolt.Tx) error { ok = get(tx, bFiles, id, &m); return nil })
	return &m, ok
}

func (s *Store) DeleteFile(id string) {
	_ = s.db.Update(func(tx *bolt.Tx) error { return tx.Bucket(bFiles).Delete([]byte(id)) })
}

func (s *Store) FilesTotal() int64 {
	var n int64
	_ = s.db.View(func(tx *bolt.Tx) error {
		return tx.Bucket(bFiles).ForEach(func(_, v []byte) error {
			var m FileMeta
			if json.Unmarshal(v, &m) == nil {
				n += m.Size
			}
			return nil
		})
	})
	return n
}

// Cleanup removes expired tokens, queue items and file records; returns expired file IDs.
func (s *Store) Cleanup() []string {
	now := time.Now()
	var files []string
	_ = s.db.Update(func(tx *bolt.Tx) error {
		var del [][]byte
		_ = tx.Bucket(bTokens).ForEach(func(k, v []byte) error {
			var r tokenRec
			if json.Unmarshal(v, &r) != nil || now.After(r.Expires) {
				del = append(del, append([]byte(nil), k...))
			}
			return nil
		})
		for _, k := range del {
			_ = tx.Bucket(bTokens).Delete(k)
		}
		del = del[:0]
		_ = tx.Bucket(bQueue).ForEach(func(k, v []byte) error {
			var q queued
			if json.Unmarshal(v, &q) != nil || now.After(q.Expires) {
				del = append(del, append([]byte(nil), k...))
			}
			return nil
		})
		for _, k := range del {
			_ = tx.Bucket(bQueue).Delete(k)
		}
		del = del[:0]
		_ = tx.Bucket(bFiles).ForEach(func(k, v []byte) error {
			var m FileMeta
			if json.Unmarshal(v, &m) != nil || now.After(m.Expires) {
				del = append(del, append([]byte(nil), k...))
				files = append(files, string(k))
			}
			return nil
		})
		for _, k := range del {
			_ = tx.Bucket(bFiles).Delete(k)
		}
		return nil
	})
	return files
}

var errNotFound = errors.New("not found")
