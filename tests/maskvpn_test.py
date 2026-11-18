#!/usr/bin/env python3
"""End-to-end test of the masquerading VPN transport + L3 tunnel.

Usage: maskvpn_test.py <server_ip> <domain> <secret> [port]

Checks:
  1. Cover site: GET / with a browser UA -> 200 (the site an active prober sees).
  2. Masquerade endpoint without a valid proof -> 204 (looks like an analytics endpoint, no error).
  3. Authenticated tunnel: WebSocket 101 upgrade, key exchange, tunnel config, then real IPv4:
     - ICMP echo to the tunnel gateway (deterministic), and
     - ICMP echo to 8.8.8.8 through the server's NAT (internet; best-effort).
  Works directly and through the L4 proxy.

Requires: pip install cryptography
"""
import base64
import hashlib
import hmac
import json
import os
import socket
import ssl
import struct
import sys
import time

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

PATH = "/app/events"
COOKIE = "__Host-sess"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
OP_CONFIG, OP_IP, OP_KA = 1, 2, 3


def hsha256(key, msg):
    return hmac.new(key, msg, hashlib.sha256).digest()


def tls_connect(ip, port, domain):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx.wrap_socket(socket.create_connection((ip, port), timeout=15), server_hostname=domain)


def http_get(ip, port, domain, path):
    s = tls_connect(ip, port, domain)
    s.sendall((f"GET {path} HTTP/1.1\r\nHost: {domain}\r\nUser-Agent: {UA}\r\nConnection: close\r\n\r\n").encode())
    data = b""
    try:
        while True:
            c = s.recv(4096)
            if not c:
                break
            data += c
    except Exception:
        pass
    s.close()
    return data.decode("latin1")


def nonce(ctr):
    return b"\x00\x00\x00\x00" + struct.pack(">Q", ctr)


def inet_cksum(b):
    if len(b) % 2:
        b += b"\x00"
    s = sum(struct.unpack("!%dH" % (len(b) // 2), b))
    s = (s >> 16) + (s & 0xFFFF)
    s += s >> 16
    return (~s) & 0xFFFF


def ipv4(src, dst, proto, payload, ident=0x1234):
    hdr = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(payload), ident, 0, 64, proto, 0,
                      socket.inet_aton(src), socket.inet_aton(dst))
    chk = inet_cksum(hdr)
    hdr = hdr[:10] + struct.pack("!H", chk) + hdr[12:]
    return hdr + payload


def icmp_echo(ident, seq, data=b"securegate-ping"):
    h = struct.pack("!BBHHH", 8, 0, 0, ident, seq)
    chk = inet_cksum(h + data)
    return struct.pack("!BBHHH", 8, 0, chk, ident, seq) + data


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(2)
    ip, domain, secret = sys.argv[1], sys.argv[2], sys.argv[3]
    port = int(sys.argv[4]) if len(sys.argv) > 4 else 443

    resp = http_get(ip, port, domain, "/")
    assert resp.startswith("HTTP/1.1 200"), "cover site did not return 200: " + resp[:80]
    print("ok  - cover site GET / -> 200")

    resp = http_get(ip, port, domain, PATH)
    assert " 204 " in resp.split("\r\n")[0], "unauth endpoint not 204: " + resp[:80]
    print("ok  - %s without proof -> 204 (looks ordinary)" % PATH)

    # authenticated tunnel upgrade
    ak = hsha256(secret.encode(), b"securegate-maskvpn-auth-v1")
    data = struct.pack(">Q", int(time.time())) + os.urandom(8)
    proof = base64.urlsafe_b64encode(data + hsha256(ak, b"proof" + data)[:16]).rstrip(b"=").decode()
    wskey = base64.b64encode(os.urandom(16)).decode()
    s = tls_connect(ip, port, domain)
    s.sendall((
        f"GET {PATH} HTTP/1.1\r\nHost: {domain}\r\nUser-Agent: {UA}\r\n"
        f"Upgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {wskey}\r\nSec-WebSocket-Version: 13\r\n"
        f"Cookie: {COOKIE}={proof}\r\n\r\n"
    ).encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        c = s.recv(1)
        if not c:
            break
        buf += c
    assert buf.startswith(b"HTTP/1.1 101"), "no 101 upgrade: " + buf[:120].decode("latin1")
    print("ok  - tunnel upgrade 101 Switching Protocols")

    c_rand = os.urandom(32)
    s.sendall(c_rand)
    s_rand = b""
    while len(s_rand) < 32:
        c = s.recv(32 - len(s_rand))
        if not c:
            break
        s_rand += c
    km = HKDF(algorithm=hashes.SHA256(), length=64, salt=c_rand + s_rand,
              info=b"securegate-maskvpn-v1").derive(hsha256(secret.encode(), b"securegate-maskvpn-tun-v1"))
    tx = ChaCha20Poly1305(km[:32])
    rx = ChaCha20Poly1305(km[32:])
    print("ok  - key exchange (HKDF-SHA256 / ChaCha20-Poly1305)")

    tn = [0]
    rn = [0]

    def send(op, payload=b""):
        ct = tx.encrypt(nonce(tn[0]), bytes([op]) + payload, None)
        tn[0] += 1
        s.sendall(struct.pack(">H", len(ct)) + ct)

    def recv_frame():
        ln = b""
        while len(ln) < 2:
            ch = s.recv(2 - len(ln))
            if not ch:
                raise EOFError
            ln += ch
        n = struct.unpack(">H", ln)[0]
        body = b""
        while len(body) < n:
            ch = s.recv(n - len(body))
            if not ch:
                raise EOFError
            body += ch
        pt = rx.decrypt(nonce(rn[0]), body, None)
        rn[0] += 1
        return pt[0], pt[1:]

    op, payload = recv_frame()
    assert op == OP_CONFIG, "first frame is not config: op=%d" % op
    cfg = json.loads(payload.decode())
    myip = cfg["address"].split("/")[0]
    gw = cfg["gateway"]
    print("ok  - tunnel config: address=%s gateway=%s dns=%s mtu=%s" % (myip, gw, cfg.get("dns"), cfg.get("mtu")))

    def ping(dst, label, timeout=6):
        seq = int(time.time()) & 0xFFFF
        send(OP_IP, ipv4(myip, dst, 1, icmp_echo(0xBEEF, seq)))
        s.settimeout(timeout)
        end = time.time() + timeout
        while time.time() < end:
            try:
                op, pkt = recv_frame()
            except (EOFError, socket.timeout):
                break
            if op != OP_IP or len(pkt) < 28 or pkt[0] >> 4 != 4:
                continue
            ihl = (pkt[0] & 0xF) * 4
            if pkt[9] != 1:   # ICMP
                continue
            src = socket.inet_ntoa(pkt[12:16])
            icmp = pkt[ihl:]
            if icmp and icmp[0] == 0 and src == dst:   # echo reply from dst
                return True
        return False

    assert ping(gw, "gateway"), "no ICMP reply from tunnel gateway %s" % gw
    print("ok  - ICMP echo to tunnel gateway %s answered (packets flow through the TUN)" % gw)

    if ping("8.8.8.8", "internet"):
        print("ok  - ICMP echo to 8.8.8.8 answered through the server NAT (real internet via VPN)")
    else:
        print("warn- no ICMP reply from 8.8.8.8 (NAT ok locally; check the server's outbound ICMP)")
    s.close()
    print("\nMASKVPN OK: cover site intact, L3 tunnel works (packets routed via TUN + NAT)")


if __name__ == "__main__":
    main()
