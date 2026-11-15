// Package certs provides TLS certificates: Let's Encrypt via ACME (autocert) with automatic
// background renewal, and a persistent self-signed fallback for testing / while ACME is failing.
package certs

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"log/slog"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/acme"
	"golang.org/x/crypto/acme/autocert"
)

const stagingURL = "https://acme-staging-v02.api.letsencrypt.org/directory"

type Manager struct {
	domain  string
	mode    string
	strict  bool
	ac      *autocert.Manager
	self    *tls.Certificate
	mu      sync.Mutex
	lastErr time.Time
	// OnSNIReject is called when a client presents a foreign SNI (strike hook).
	OnSNIReject func(hello *tls.ClientHelloInfo)
}

func New(domain, mode, email, dataDir string, staging, strictSNI bool) (*Manager, error) {
	m := &Manager{domain: domain, mode: mode, strict: strictSNI}
	if mode != "selfsigned" {
		m.ac = &autocert.Manager{
			Prompt:      autocert.AcceptTOS,
			HostPolicy:  autocert.HostWhitelist(domain),
			Cache:       autocert.DirCache(filepath.Join(dataDir, "acme")),
			Email:       email,
			RenewBefore: 30 * 24 * time.Hour,
		}
		if staging {
			m.ac.Client = &acme.Client{DirectoryURL: stagingURL}
		}
	}
	if mode != "letsencrypt" {
		c, err := loadOrCreateSelfSigned(domain, filepath.Join(dataDir, "selfsigned"))
		if err != nil {
			return nil, err
		}
		m.self = c
	}
	return m, nil
}

// TLSConfig returns the server TLS config used on :443.
func (m *Manager) TLSConfig() *tls.Config {
	return &tls.Config{
		MinVersion:     tls.VersionTLS12,
		NextProtos:     []string{"http/1.1", acme.ALPNProto},
		GetCertificate: m.getCertificate,
	}
}

func (m *Manager) getCertificate(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
	sni := strings.ToLower(strings.TrimSuffix(hello.ServerName, "."))
	if sni != "" && sni != m.domain {
		if m.OnSNIReject != nil {
			m.OnSNIReject(hello)
		}
		return nil, errors.New("unknown server name")
	}
	if sni == "" && m.strict {
		if m.OnSNIReject != nil {
			m.OnSNIReject(hello)
		}
		return nil, errors.New("missing server name")
	}
	if m.ac == nil {
		return m.self, nil
	}
	h := *hello
	h.ServerName = m.domain
	c, err := m.ac.GetCertificate(&h)
	if err == nil {
		return c, nil
	}
	m.mu.Lock()
	logIt := time.Since(m.lastErr) > time.Minute
	if logIt {
		m.lastErr = time.Now()
	}
	m.mu.Unlock()
	if logIt {
		slog.Error("certs: ACME certificate unavailable", "err", err)
	}
	if m.self != nil {
		return m.self, nil
	}
	return nil, err
}

// HTTPHandler wraps the port-80 handler to answer ACME HTTP-01 challenges.
func (m *Manager) HTTPHandler(fallback http.Handler) http.Handler {
	if m.ac == nil {
		return fallback
	}
	return m.ac.HTTPHandler(fallback)
}

// RunRenewal is the background certificate job: obtains the certificate at startup and
// re-checks every 12 hours (autocert renews 30 days before expiry).
func (m *Manager) RunRenewal(ctx context.Context) {
	if m.ac == nil {
		slog.Info("certs: self-signed mode, ACME disabled")
		return
	}
	check := func() {
		hello := &tls.ClientHelloInfo{
			ServerName:        m.domain,
			CipherSuites:      []uint16{tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256},
			SignatureSchemes:  []tls.SignatureScheme{tls.ECDSAWithP256AndSHA256, tls.PSSWithSHA256},
			SupportedCurves:   []tls.CurveID{tls.CurveP256, tls.X25519},
			SupportedVersions: []uint16{tls.VersionTLS13, tls.VersionTLS12},
		}
		c, err := m.ac.GetCertificate(hello)
		if err != nil {
			slog.Error("certs: obtain/renew failed (will retry)", "domain", m.domain, "err", err)
			return
		}
		if c.Leaf == nil && len(c.Certificate) > 0 {
			c.Leaf, _ = x509.ParseCertificate(c.Certificate[0])
		}
		if c.Leaf != nil {
			slog.Info("certs: certificate OK", "domain", m.domain, "issuer", c.Leaf.Issuer.CommonName,
				"expires", c.Leaf.NotAfter.Format(time.RFC3339), "days_left", int(time.Until(c.Leaf.NotAfter).Hours()/24))
		}
	}
	timer := time.NewTimer(3 * time.Second)
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			check()
			timer.Reset(12 * time.Hour)
		}
	}
}

func loadOrCreateSelfSigned(domain, dir string) (*tls.Certificate, error) {
	certPath := filepath.Join(dir, "cert.pem")
	keyPath := filepath.Join(dir, "key.pem")
	if c, err := tls.LoadX509KeyPair(certPath, keyPath); err == nil {
		if leaf, err := x509.ParseCertificate(c.Certificate[0]); err == nil &&
			time.Until(leaf.NotAfter) > 30*24*time.Hour && leaf.VerifyHostname(domain) == nil {
			return &c, nil
		}
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	tpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: domain},
		DNSNames:              []string{domain},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(2, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	kb, _ := x509.MarshalECPrivateKey(key)
	_ = os.MkdirAll(dir, 0o700)
	_ = os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644)
	_ = os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb}), 0o600)
	c, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, err
	}
	slog.Info("certs: generated self-signed certificate", "domain", domain)
	return &c, nil
}
