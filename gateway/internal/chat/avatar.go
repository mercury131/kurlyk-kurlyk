package chat

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// Avatars are uploaded already encrypted by the client (nonce12 || AES-256-GCM(content_key, jpeg)),
// exactly like files: the server stores an opaque blob and cannot see the picture.
// Every user object carries "avatar_ver" (unix ms of the last change, 0 = no avatar) so clients
// know when to re-download; changes are broadcast as {"type":"user"} events.

const maxAvatarBytes = 512 << 10

func (s *Server) avatarPath(phone string) string {
	h := sha256.Sum256([]byte(phone))
	return filepath.Join(s.cfg.DataDir, "avatars", hex.EncodeToString(h[:16])+".bin")
}

func (s *Server) handleSetAvatar(w http.ResponseWriter, r *http.Request, phone string) {
	if r.ContentLength > maxAvatarBytes {
		apiErr(w, 413, "avatar too large (max 512 KB)")
		return
	}
	b, err := io.ReadAll(io.LimitReader(r.Body, maxAvatarBytes+1))
	if err != nil || len(b) < 29 { // at least nonce(12) + tag(16) + 1 byte
		apiErr(w, 400, "bad avatar data")
		return
	}
	if len(b) > maxAvatarBytes {
		apiErr(w, 413, "avatar too large (max 512 KB)")
		return
	}
	path := s.avatarPath(phone)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		apiErr(w, 500, "storage error")
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		apiErr(w, 500, "storage error")
		return
	}
	u, _ := s.store.User(phone)
	u.AvatarVer = time.Now().UnixMilli()
	_ = s.store.PutUser(u)
	s.hub.broadcastUser(*u)
	writeJSON(w, 200, s.userView(*u))
}

func (s *Server) handleDeleteAvatar(w http.ResponseWriter, phone string) {
	_ = os.Remove(s.avatarPath(phone))
	u, _ := s.store.User(phone)
	u.AvatarVer = 0
	_ = s.store.PutUser(u)
	s.hub.broadcastUser(*u)
	writeJSON(w, 200, s.userView(*u))
}

func (s *Server) handleGetAvatar(w http.ResponseWriter, r *http.Request, rawPhone string) {
	phone, ok := NormalizePhone(rawPhone)
	if !ok {
		apiErr(w, 404, "not found")
		return
	}
	u, exists := s.store.User(phone)
	if !exists || u.AvatarVer == 0 {
		apiErr(w, 404, "no avatar")
		return
	}
	f, err := os.Open(s.avatarPath(phone))
	if err != nil {
		apiErr(w, 404, "no avatar")
		return
	}
	defer f.Close()
	etag := `"` + strconv.FormatInt(u.AvatarVer, 10) + `"`
	w.Header().Set("ETag", etag)
	w.Header().Set("X-Avatar-Ver", strconv.FormatInt(u.AvatarVer, 10))
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "private, max-age=0, must-revalidate")
	http.ServeContent(w, r, "avatar", time.UnixMilli(u.AvatarVer), f)
}
