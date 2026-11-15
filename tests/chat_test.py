#!/usr/bin/env python3
"""End-to-end test of the chat server API (reference client implementation).

Needs: pip install websockets cryptography
Usage: chat_test.py <domain> <chat_secret>   (the domain must resolve to the server, e.g. --add-host)

Also serves as the reference for the Android client crypto:
  auth_key    = HMAC-SHA256(key=secret_utf8, msg="securegate-chat-auth-v1")
  content_key = HMAC-SHA256(key=secret_utf8, msg="securegate-chat-content-v1")
  sig         = hex(HMAC-SHA256(auth_key, "v1|<phone>|<ts>|<nonce>"))
  payload     = base64(nonce12 || AES-256-GCM(content_key, nonce12, json_utf8))
"""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import ssl
import sys
import time
import urllib.request
import urllib.error

import websockets
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

DOMAIN, SECRET = sys.argv[1], sys.argv[2]
BASE = f"https://{DOMAIN}/chat/api"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

AUTH_KEY = hmac.new(SECRET.encode(), b"securegate-chat-auth-v1", hashlib.sha256).digest()
CONTENT_KEY = hmac.new(SECRET.encode(), b"securegate-chat-content-v1", hashlib.sha256).digest()
UA = {"User-Agent": "SecureChat-Android/1.0 (test)"}


def encrypt(obj) -> str:
    nonce = os.urandom(12)
    ct = AESGCM(CONTENT_KEY).encrypt(nonce, json.dumps(obj).encode(), None)
    return base64.b64encode(nonce + ct).decode()


def decrypt(payload: str):
    raw = base64.b64decode(payload)
    return json.loads(AESGCM(CONTENT_KEY).decrypt(raw[:12], raw[12:], None))


def api(method, path, token=None, body=None, raw=None):
    headers = dict(UA)
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if raw is not None:
        data = raw
        headers["Content-Type"] = "application/octet-stream"
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, context=CTX, timeout=15) as r:
            b = r.read()
            return r.status, (json.loads(b) if r.headers.get("Content-Type", "").startswith("application/json") else b)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def auth(phone, name, secret_key=AUTH_KEY):
    ts = int(time.time())
    nonce = os.urandom(16).hex()
    sig = hmac.new(secret_key, f"v1|{phone}|{ts}|{nonce}".encode(), hashlib.sha256).hexdigest()
    return api("POST", "/auth", body={"phone": phone, "name": name, "ts": ts, "nonce": nonce, "sig": sig})


ok_count = 0


def check(cond, what):
    global ok_count
    if not cond:
        print("FAIL:", what)
        sys.exit(1)
    ok_count += 1
    print("ok  -", what)


async def recv_until(ws, typ, timeout=5, pred=lambda m: True):
    end = time.time() + timeout
    while time.time() < end:
        m = json.loads(await asyncio.wait_for(ws.recv(), timeout=end - time.time()))
        if m.get("type") == typ and pred(m):
            return m
    raise TimeoutError(typ)


async def ws_connect(token):
    return await websockets.connect(f"wss://{DOMAIN}/chat/ws", ssl=CTX,
                                    additional_headers={"Authorization": "Bearer " + token, **UA})


async def main():
    A, B, C = "+79990000001", "+79990000002", "+79990000003"
    st, r = auth(A, "Alice", hmac.new(b"wrong-secret-xxxxxxxx", b"securegate-chat-auth-v1", hashlib.sha256).digest())
    check(st == 401, "wrong secret rejected (401)")
    st, ra = auth(A, "Alice")
    check(st == 200 and ra.get("token"), "Alice authenticated")
    st, rb = auth(B, "Bob")
    check(st == 200, "Bob authenticated")
    st, rc = auth(C, "Carol")
    check(st == 200, "Carol authenticated")
    ta, tb, tc = ra["token"], rb["token"], rc["token"]

    st, users = api("GET", "/users", ta)
    phones = {u["phone"] for u in users["users"]}
    check(st == 200 and {A, B, C} <= phones, "user directory lists all users")
    st, cfg = api("GET", "/config", ta)
    check(st == 200 and cfg["ice_servers"], "config with ICE servers: %s" % json.dumps(cfg["ice_servers"])[:160])
    st, _ = api("GET", "/users", "badtoken")
    check(st == 401, "bad token rejected")

    wa = await ws_connect(ta)
    await recv_until(wa, "hello")
    wb = await ws_connect(tb)
    await recv_until(wb, "hello")
    p = await recv_until(wa, "presence", pred=lambda m: m["phone"] == B)
    check(p["online"], "Alice sees Bob online (presence)")

    # 1:1 message + receipts
    body = {"kind": "text", "text": "Привет, Боб! 🔐", "sent_at": int(time.time() * 1000)}
    await wa.send(json.dumps({"type": "msg", "id": "m1", "to": B, "payload": encrypt(body)}))
    s = await recv_until(wa, "sent")
    check(s["id"] == "m1", "server confirmed 'sent'")
    m = await recv_until(wb, "msg")
    check(m["from"] == A and decrypt(m["payload"]) == body, "Bob received and decrypted message")
    await wb.send(json.dumps({"type": "ack", "qids": [m["qid"]]}))
    rc1 = await recv_until(wa, "receipt")
    check(rc1["status"] == "delivered" and rc1["ids"] == ["m1"], "Alice got 'delivered' receipt")
    await wa.send(json.dumps({"type": "ack", "qids": [rc1["qid"]]}))
    await wb.send(json.dumps({"type": "read", "to": A, "ids": ["m1"]}))
    rc2 = await recv_until(wa, "receipt")
    check(rc2["status"] == "read", "Alice got 'read' receipt")
    await wa.send(json.dumps({"type": "ack", "qids": [rc2["qid"]]}))

    # typing
    await wa.send(json.dumps({"type": "typing", "to": B}))
    t = await recv_until(wb, "typing")
    check(t["from"] == A, "typing indicator relayed")

    # avatars (encrypted blob, version in user objects, broadcast as "user" event)
    pic = os.urandom(20_000)
    nonce = os.urandom(12)
    st, me = api("PUT", "/me/avatar", ta, raw=nonce + AESGCM(CONTENT_KEY).encrypt(nonce, pic, None))
    check(st == 200 and me["avatar_ver"] > 0, "Alice uploaded avatar")
    ue = await recv_until(wb, "user", pred=lambda m: m["user"]["phone"] == A)
    check(ue["user"]["avatar_ver"] == me["avatar_ver"], "Bob notified about Alice's new avatar")
    st, got = api("GET", "/avatars/" + A.replace("+", "%2B"), tb)
    check(st == 200 and AESGCM(CONTENT_KEY).decrypt(got[:12], got[12:], None) == pic, "Bob downloaded and decrypted Alice's avatar")
    st, users = api("GET", "/users", tb)
    check(any(u["phone"] == A and u["avatar_ver"] == me["avatar_ver"] for u in users["users"]), "avatar_ver present in /users")

    # offline queue
    await wb.close()
    await recv_until(wa, "presence", pred=lambda m: m["phone"] == B and not m["online"])
    await wa.send(json.dumps({"type": "msg", "id": "m2", "to": B, "payload": encrypt({"kind": "text", "text": "ты оффлайн"})}))
    await recv_until(wa, "sent")
    wb = await ws_connect(tb)
    m2 = await recv_until(wb, "msg", pred=lambda m: m["id"] == "m2")
    check(decrypt(m2["payload"])["text"] == "ты оффлайн", "offline message delivered after reconnect")
    await wb.send(json.dumps({"type": "ack", "qids": [m2["qid"]]}))

    # groups
    st, g = api("POST", "/groups", ta, {"name": "Команда", "members": [B, C]})
    check(st == 200 and len(g["members"]) == 3, "group created")
    ge = await recv_until(wb, "group")
    check(ge["group"]["id"] == g["id"], "Bob notified about the group")
    await wb.send(json.dumps({"type": "ack", "qids": [ge["qid"]]}))
    await wa.send(json.dumps({"type": "msg", "id": "g1", "to": "group:" + g["id"], "payload": encrypt({"kind": "text", "text": "всем привет"})}))
    gm = await recv_until(wb, "msg", pred=lambda m: m["id"] == "g1")
    check(gm.get("group") == g["id"], "group message delivered to Bob")
    await wb.send(json.dumps({"type": "ack", "qids": [gm["qid"]]}))
    st, gl = api("GET", "/groups", tc)
    check(st == 200 and any(x["id"] == g["id"] for x in gl["groups"]), "Carol sees the group")

    # files
    blob = os.urandom(300_000)
    nonce = os.urandom(12)
    enc = nonce + AESGCM(CONTENT_KEY).encrypt(nonce, blob, None)
    st, f = api("POST", "/files", ta, raw=enc)
    check(st == 200 and f["size"] == len(enc), "encrypted file uploaded (%d bytes)" % len(enc))
    st, got = api("GET", "/files/" + f["id"], tb)
    dec = AESGCM(CONTENT_KEY).decrypt(got[:12], got[12:], None)
    check(st == 200 and dec == blob, "Bob downloaded and decrypted the file")

    # calls (signaling)
    await wa.send(json.dumps({"type": "call", "to": B, "call_id": "c1", "action": "offer",
                              "data": {"sdp": "v=0...", "video": True}}))
    co = await recv_until(wb, "call")
    check(co["action"] == "offer" and co["data"]["video"], "call offer relayed to Bob")
    await wb.send(json.dumps({"type": "call", "to": A, "call_id": "c1", "action": "answer", "data": {"sdp": "v=0..."}}))
    ca = await recv_until(wa, "call")
    check(ca["action"] == "answer", "call answer relayed to Alice")
    await wa.send(json.dumps({"type": "call", "to": C, "call_id": "c2", "action": "offer", "data": {}}))
    cu = await recv_until(wa, "call")
    check(cu["action"] == "unavailable", "offer to offline user -> unavailable")

    await wa.close()
    await wb.close()
    print(f"\nCHAT OK: {ok_count} checks passed")


asyncio.run(main())
