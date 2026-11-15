#!/usr/bin/env python3
"""MTProxy end-to-end check: behaves like a real Telegram client.

FakeTLS ClientHello (HMAC'ed client_random) -> verify FakeTLS ServerHello ->
obfuscated2 handshake inside TLS application-data records (padded intermediate, 0xdddddddd) ->
unencrypted MTProto req_pq_multi to the given DC -> expects resPQ from Telegram.

So it proves the whole path: gateway detector -> telemt -> Telegram DC -> back.

Needs: python3 + cryptography (pip install cryptography).
Usage: mtproxy_e2e_test.py <server_ip> <domain> <32hex_secret> [port] [dc]
"""
import hashlib
import hmac
import os
import socket
import ssl
import struct
import sys
import time

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


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


def recv_exact(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise EOFError("connection closed after %d/%d bytes" % (len(b), n))
        b += c
    return b


def read_record(s):
    h = recv_exact(s, 5)
    ln = struct.unpack(">H", h[3:5])[0]
    return h, recv_exact(s, ln)


def ctr(key, iv):
    return Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()


def main():
    ip, domain, secret_hex = sys.argv[1], sys.argv[2], sys.argv[3]
    port = int(sys.argv[4]) if len(sys.argv) > 4 else 443
    dc = int(sys.argv[5]) if len(sys.argv) > 5 else 2
    secret = bytes.fromhex(secret_hex)

    # --- FakeTLS ClientHello ---
    hello = bytearray(client_hello(domain))
    hello[11:43] = b"\x00" * 32
    digest = hmac.new(secret, bytes(hello), hashlib.sha256).digest()
    ts = struct.pack("<I", int(time.time()))
    rnd = digest[:28] + bytes(a ^ b for a, b in zip(digest[28:], ts))
    hello[11:43] = rnd

    s = socket.create_connection((ip, port), timeout=15)
    s.sendall(bytes(hello))

    # --- FakeTLS server answer: ServerHello(0x16) + CCS(0x14) + AppData(0x17) ---
    resp = b""
    recs = []
    while True:
        h, body = read_record(s)
        recs.append(h[0])
        resp += h + body
        if h[0] == 0x17:
            break
    zeroed = bytearray(resp)
    zeroed[11:43] = b"\x00" * 32
    expect = hmac.new(secret, rnd + bytes(zeroed), hashlib.sha256).digest()
    if resp[11:43] != expect:
        print("FAIL: FakeTLS ServerHello HMAC mismatch (records %s)" % recs)
        sys.exit(1)
    print("step 1 OK: FakeTLS handshake (records %s, %d bytes)" % (["%02x" % r for r in recs], len(resp)))

    # --- obfuscated2 init ---
    while True:
        init = bytearray(os.urandom(64))
        if init[0] == 0xEF:
            continue
        if init[:4] in (b"HEAD", b"POST", b"GET ", b"OPTI", b"\xdd\xdd\xdd\xdd", b"\xee\xee\xee\xee", b"\x16\x03\x01\x02"):
            continue
        if init[4:8] == b"\x00\x00\x00\x00":
            continue
        break
    init[56:60] = b"\xdd\xdd\xdd\xdd"
    init[60:62] = struct.pack("<h", dc)
    rev = bytes(init[8:56])[::-1]
    enc = ctr(hashlib.sha256(bytes(init[8:40]) + secret).digest(), bytes(init[40:56]))
    dec = ctr(hashlib.sha256(rev[:32] + secret).digest(), rev[32:48])
    encrypted = enc.update(bytes(init))
    init_out = bytes(init[:56]) + encrypted[56:64]

    # --- MTProto req_pq_multi (unencrypted) ---
    nonce = os.urandom(16)
    body = struct.pack("<I", 0xBE7E8EF1) + nonce
    msg_id = (int(time.time() * 2**32)) & ~3
    mt = struct.pack("<qqi", 0, msg_id, len(body)) + body
    pad = os.urandom(4)
    frame = struct.pack("<I", len(mt) + len(pad)) + mt + pad
    payload = init_out + enc.update(frame)

    s.sendall(b"\x14\x03\x03\x00\x01\x01" + b"\x17\x03\x03" + struct.pack(">H", len(payload)) + payload)

    # --- read answer ---
    buf = b""
    s.settimeout(20)
    while True:
        h, data = read_record(s)
        if h[0] != 0x17:
            continue
        buf += dec.update(data)
        if len(buf) >= 4:
            ln = struct.unpack("<I", buf[:4])[0] & 0x7FFFFFFF
            if len(buf) >= 4 + ln:
                break
    pkt = buf[4:4 + ln]
    if len(pkt) >= 4 and len(pkt) <= 8:
        print("FAIL: Telegram returned transport error %d" % struct.unpack("<i", pkt[:4])[0])
        sys.exit(1)
    auth_key_id, _, mlen = struct.unpack("<qqi", pkt[:20])
    ctor = struct.unpack("<I", pkt[20:24])[0]
    if auth_key_id == 0 and ctor == 0x05162463 and pkt[24:40] == nonce:
        print("MTPROXY E2E OK: resPQ from Telegram DC %d through proxy (%d bytes)" % (dc, len(pkt)))
    else:
        print("FAIL: unexpected answer ctor=%08x len=%d" % (ctor, len(pkt)))
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa
        print("FAIL:", type(e).__name__, e)
        sys.exit(1)
