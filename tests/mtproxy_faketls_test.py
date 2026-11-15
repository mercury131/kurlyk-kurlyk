#!/usr/bin/env python3
"""MTProxy FakeTLS end-to-end check (stdlib only).

Builds a real TLS ClientHello (via Python ssl + MemoryBIO), turns it into a Telegram
FakeTLS hello (client_random = HMAC-SHA256(secret, hello) ^ timestamp), sends it to the
gateway on :443 and verifies that the answer is telemt's FakeTLS ServerHello
(server_random = HMAC-SHA256(secret, client_random || response_with_zero_random)).

Usage: mtproxy_faketls_test.py <server_ip> <domain> <32hex_secret> [port]
"""
import hashlib
import hmac
import socket
import ssl
import struct
import sys
import time


def client_hello(domain: str) -> bytes:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    inc, out = ssl.MemoryBIO(), ssl.MemoryBIO()
    obj = ctx.wrap_bio(inc, out, server_hostname=domain)
    try:
        obj.do_handshake()
    except ssl.SSLWantReadError:
        pass
    return out.read()


def main():
    ip, domain, secret_hex = sys.argv[1], sys.argv[2], sys.argv[3]
    port = int(sys.argv[4]) if len(sys.argv) > 4 else 443
    secret = bytes.fromhex(secret_hex)

    hello = bytearray(client_hello(domain))
    hello[11:43] = b"\x00" * 32
    digest = hmac.new(secret, bytes(hello), hashlib.sha256).digest()
    ts = struct.pack("<I", int(time.time()))
    rnd = digest[:28] + bytes(a ^ b for a, b in zip(digest[28:], ts))
    hello[11:43] = rnd

    s = socket.create_connection((ip, port), timeout=10)
    s.sendall(bytes(hello))
    resp = b""
    deadline = time.time() + 10
    while time.time() < deadline:
        try:
            chunk = s.recv(65536)
        except socket.timeout:
            break
        if not chunk:
            break
        resp += chunk
        # ServerHello + CCS + ApplicationData records
        if len(resp) > 200:
            time.sleep(0.3)
            s.settimeout(0.5)
            try:
                resp += s.recv(65536)
            except Exception:
                pass
            break
    s.close()
    if len(resp) < 43 or resp[0] != 0x16:
        print("FAIL: no TLS ServerHello from proxy, got %d bytes: %r" % (len(resp), resp[:16]))
        sys.exit(1)
    srv_random = resp[11:43]
    zeroed = bytearray(resp)
    zeroed[11:43] = b"\x00" * 32
    expect = hmac.new(secret, rnd + bytes(zeroed), hashlib.sha256).digest()
    if srv_random == expect:
        print("MTPROXY OK: telemt accepted FakeTLS hello and answered with valid HMAC (%d bytes)" % len(resp))
    else:
        print("FAIL: got TLS response but server_random HMAC mismatch (%d bytes) - not telemt?" % len(resp))
        sys.exit(1)


if __name__ == "__main__":
    main()
