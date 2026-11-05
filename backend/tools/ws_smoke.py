#!/usr/bin/env python3
"""End-to-end test for the NusaChat WebSocket gateway.

Usage:
    python3 tools/ws_smoke.py [api_base] [ws_url]

Requires the API (PHP built-in server) and the Erlang gateway
(realtime/nusachat_realtime, port 8091) to be running.
"""
import base64
import hashlib
import hmac
import json
import os
import socket
import struct
import sys
import time
import urllib.request
import uuid

API = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080/api/v1").rstrip("/")
WS_URL = sys.argv[2] if len(sys.argv) > 2 else "ws://127.0.0.1:8091"
PASSWORD = "password123"

failures = []
checks = 0


def check(label, condition, detail=""):
    global checks
    checks += 1
    if condition:
        print(f"  ok  {label}")
    else:
        failures.append(f"{label} {detail}")
        print(f" FAIL {label} {detail}")


def http(method, path, data=None, token=None, timeout=20):
    headers = {"Accept": "application/json"}
    body = None
    if data is not None:
        body = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(API + path, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode())


def login(name):
    _, payload = http("POST", "/auth/login", {"login": name, "password": PASSWORD, "device": "ws-smoke"})
    return payload["data"]["auth"]["access_token"]


class WebSocket:
    def __init__(self, url, token, timeout=10):
        assert url.startswith("ws://")
        rest = url[5:]
        host_port, _, path = rest.partition("/")
        host, _, port = host_port.partition(":")
        port = int(port or 80)
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET /{path}?token={token} HTTP/1.1\r\n"
            f"Host: {host_port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(request.encode())
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            response += chunk
        self.handshake = response.decode(errors="replace")
        self.buffer = response.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in response else b""

    def send_text(self, text):
        payload = text.encode()
        mask = os.urandom(4)
        header = bytes([0x81])
        length = len(payload)
        if length < 126:
            header += bytes([0x80 | length])
        elif length < 65536:
            header += bytes([0x80 | 126]) + struct.pack("!H", length)
        else:
            header += bytes([0x80 | 127]) + struct.pack("!Q", length)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def _read_exact(self, count):
        while len(self.buffer) < count:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("socket closed")
            self.buffer += chunk
        data, self.buffer = self.buffer[:count], self.buffer[count:]
        return data

    def read_frame(self):
        first, second = self._read_exact(2)
        opcode = first & 0x0F
        masked = bool(second & 0x80)
        length = second & 0x7F
        if length == 126:
            length = struct.unpack("!H", self._read_exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self._read_exact(8))[0]
        mask = self._read_exact(4) if masked else b""
        payload = self._read_exact(length)
        if masked:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return opcode, payload

    def read_json(self, timeout=8, predicate=None):
        deadline = time.time() + timeout
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError("no matching frame received")
            self.sock.settimeout(remaining)
            opcode, payload = self.read_frame()
            if opcode == 0x9:  # ping -> pong
                self.sock.sendall(self._frame(0xA, payload))
                continue
            if opcode == 0x8:
                raise ConnectionError("server closed the connection")
            if opcode in (0x1, 0x2):
                event = json.loads(payload.decode())
                if predicate is None or predicate(event):
                    return event

    @staticmethod
    def _frame(opcode, payload):
        header = bytes([0x80 | opcode])
        length = len(payload)
        if length < 126:
            header += bytes([length])
        elif length < 65536:
            header += bytes([126]) + struct.pack("!H", length)
        else:
            header += bytes([127]) + struct.pack("!Q", length)
        return header + payload

    def close(self):
        try:
            self.sock.sendall(self._frame(0x8, struct.pack("!H", 1000)))
        except OSError:
            pass
        self.sock.close()


def main():
    print(f"NusaChat WS smoke test -> {WS_URL}")

    # Reject an invalid token at the handshake.
    bad = WebSocket(WS_URL, "invalid.token.value")
    check("reject invalid token", "401" in bad.handshake.split("\r\n")[0], bad.handshake.split("\r\n")[0])
    bad.close()

    nusa = login("nusa")
    sari = login("sari")

    _, payload = http("GET", "/conversations", token=nusa)
    conversations = payload["data"]["conversations"]
    target = next((c for c in conversations if c.get("peer", {}).get("username") == "sari"), None)
    if not target:
        _, payload = http("POST", "/conversations/create_direct", {"user_id": 2}, token=nusa)
        target = payload["data"]["conversation"]
    conv_id = target["id"]

    ws = WebSocket(WS_URL, nusa)
    check("handshake 101", "101" in ws.handshake.split("\r\n")[0], ws.handshake.split("\r\n")[0])
    hello = ws.read_json(predicate=lambda event: event.get("type") == "connected")
    check("connected event", hello["data"]["user_id"] == 1, str(hello))

    ws.send_text(json.dumps({"type": "ping"}))
    pong = ws.read_json(predicate=lambda event: event.get("type") == "pong")
    check("ping/pong", pong["type"] == "pong", str(pong))

    body = "WS smoke " + uuid.uuid4().hex[:8]
    status, payload = http("POST", "/conversations/send", {
        "id": conv_id,
        "type": "text",
        "body": body,
        "client_id": "ws-" + uuid.uuid4().hex,
    }, token=sari)
    check("send via API", status == 201, f"status={status} {payload}")

    event = ws.read_json(predicate=lambda item: (
        item.get("type") == "message"
        and item.get("data", {}).get("conversation_id") == conv_id
        and item.get("data", {}).get("message", {}).get("body") == body
    ))
    check("message event", event["type"] == "message", str(event)[:300])

    # Call handshake: offer/answer/ICE must reach the peer intact and in
    # order. The mobile client queues remote ICE until the remote SDP is
    # applied, so every candidate delivered here must arrive unmodified.
    _, me = http("GET", "/users/me", token=sari)
    sari_id = me["data"]["user"]["id"]
    ws_sari = WebSocket(WS_URL, sari)
    ws_sari.read_json(predicate=lambda e: e.get("type") == "connected")

    sdp_offer = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=smoke-offer\r\nt=0 0\r\n"
    status, payload = http("POST", "/calls/offer", {
        "to_user_id": sari_id, "sdp": sdp_offer, "kind": "audio",
    }, token=nusa)
    check("call offer", status == 201 and payload.get("ok"), f"status={status} {payload}")
    call_id = payload["data"]["call"]["id"]
    offer_ev = ws_sari.read_json(predicate=lambda e: (
        e.get("type") == "call_offer" and e.get("data", {}).get("call", {}).get("id") == call_id
    ))
    check("call_offer event", offer_ev["data"].get("sdp") == sdp_offer, str(offer_ev)[:200])

    sdp_answer = "v=0\r\no=- 2 2 IN IP4 127.0.0.1\r\ns=smoke-answer\r\nt=0 0\r\n"
    status, payload = http("POST", "/calls/answer", {
        "call_id": call_id, "sdp": sdp_answer,
    }, token=sari)
    check("call answer", status == 200 and payload.get("ok"), f"status={status} {payload}")
    answer_ev = ws.read_json(predicate=lambda e: (
        e.get("type") == "call_answer" and e.get("data", {}).get("call", {}).get("id") == call_id
    ))
    check("call_answer event", answer_ev["data"].get("sdp") == sdp_answer, str(answer_ev)[:200])

    cand = {
        "candidate": "candidate:1 1 udp 2113937151 192.168.70.92 5000 typ host",
        "sdpMid": "0",
        "sdpMLineIndex": 0,
    }
    status, payload = http("POST", "/calls/ice", {
        "call_id": call_id, "candidate": cand,
    }, token=nusa)
    check("call ice send", status == 200 and payload.get("ok"), f"status={status} {payload}")
    ice_ev = ws_sari.read_json(predicate=lambda e: (
        e.get("type") == "call_ice" and e.get("data", {}).get("call", {}).get("id") == call_id
    ))
    got = ice_ev["data"].get("candidate", {})
    check("call_ice intact", got == cand, str(ice_ev)[:200])

    status, payload = http("POST", "/calls/end", {"call_id": call_id}, token=nusa)
    check("call end", status == 200 and payload.get("ok"), f"status={status} {payload}")

    # Signaling NVP/1 (nusa.call.v1) + relay UDP + echo + budget.
    if "8091" in WS_URL:
        run_nvp_checks(nusa, sari, sari_id, ws, ws_sari)

    # Tulis-buta via socket: ack/read/typing tanpa REST (hibrida).
    run_client_event_checks(nusa, sari, conv_id, ws, ws_sari)

    # Presence berbasis socket: connect/disconnect menyiarkan online/offline
    # ke semua teman chat, tanpa heartbeat polling.
    run_presence_checks(nusa, ws)

    # Live nearby via Erlang (no PHP polling): location.update answers
    # nearby.result, later updates push nearby.changed to subscribers.
    # Gateway satu-satunya adalah Erlang (8091).
    if "8091" in WS_URL:
        run_nearby_live_checks(nusa, sari)

    ws_sari.close()
    ws.close()
    return finish()


def peer_online(token, username):
    _, payload = http("GET", "/conversations", token=token)
    convs = payload.get("data", {}).get("conversations", []) if payload.get("ok") else []
    for conv in convs:
        if conv.get("peer", {}).get("username") == username:
            return conv.get("peer", {}).get("online")
    return None


def run_presence_checks(nusa, ws):
    # Budi berteman + berbagi chat seed dengan nusa, dan tak punya socket
    # lain selama smoke ini berjalan.
    budi = login("budi")
    _, me = http("GET", "/users/me", token=budi)
    budi_id = me["data"]["user"]["id"]

    ws_budi = WebSocket(WS_URL, budi)
    ws_budi.read_json(predicate=lambda e: e.get("type") == "connected")
    try:
        ev = ws.read_json(predicate=lambda e: (
            e.get("type") == "presence" and e.get("data", {}).get("user_id") == budi_id
        ))
        check("presence online event", ev["data"].get("online") is True, str(ev)[:200])
    except (TimeoutError, ConnectionError) as exc:
        check("presence online event", False, f"no event: {exc}")
    check("peer online true", peer_online(nusa, "budi") is True)

    ws_budi.close()
    try:
        ev = ws.read_json(predicate=lambda e: (
            e.get("type") == "presence"
            and e.get("data", {}).get("user_id") == budi_id
            and e.get("data", {}).get("online") is False
        ))
        check("presence offline event", True, str(ev)[:200])
    except (TimeoutError, ConnectionError) as exc:
        check("presence offline event", False, f"no event: {exc}")
    check("peer online false", peer_online(nusa, "budi") is False)


def run_nearby_live_checks(nusa, sari):
    ws_n = WebSocket(WS_URL, nusa)
    ws_n.read_json(predicate=lambda e: e.get("type") == "connected")
    ws_n.send_text(json.dumps({"type": "location.update", "data": {"lat": -6.2, "lng": 106.816, "radius": 50}}))
    result = ws_n.read_json(predicate=lambda e: e.get("type") in ("nearby.result", "nearby.error"))
    check("nearby.result live", result.get("type") == "nearby.result" and result.get("data", {}).get("live") is True, str(result)[:200])
    ws_s = WebSocket(WS_URL, sari)
    ws_s.read_json(predicate=lambda e: e.get("type") == "connected")
    ws_s.send_text(json.dumps({"type": "location.update", "data": {"lat": -6.201, "lng": 106.817, "radius": 50}}))
    result_s = ws_s.read_json(predicate=lambda e: e.get("type") in ("nearby.result", "nearby.error"))
    seen = [u.get("username") for u in result_s.get("data", {}).get("users", [])]
    check("nearby sees peer", "nusa" in seen, str(seen))
    changed = ws_n.read_json(predicate=lambda e: e.get("type") == "nearby.changed")
    check("nearby.changed push", changed.get("data", {}).get("user", {}).get("username") == "sari", str(changed)[:200])
    ws_n.send_text(json.dumps({"type": "location.update", "data": {"lat": 500, "lng": 10}}))
    err = ws_n.read_json(predicate=lambda e: e.get("type") == "nearby.error")
    check("nearby rejects bad coords", err.get("data", {}).get("code") == "invalid_location", str(err)[:200])
    # Regression: multi-candidate sort (crashed when >1 candidate in radius).
    budi = login("budi")
    ws_b = WebSocket(WS_URL, budi)
    ws_b.read_json(predicate=lambda e: e.get("type") == "connected")
    ws_b.send_text(json.dumps({"type": "location.update", "data": {"lat": -6.2005, "lng": 106.8165, "radius": 50}}))
    result_b = ws_b.read_json(predicate=lambda e: e.get("type") in ("nearby.result", "nearby.error"))
    users_b = result_b.get("data", {}).get("users", [])
    dists = [u.get("distance_km") for u in users_b]
    check(
        "nearby multi sorted",
        result_b.get("type") == "nearby.result" and len(users_b) >= 2
        and all(isinstance(d, (int, float)) for d in dists)
        and dists == sorted(dists),
        f"n={len(users_b)} dists={dists}",
    )
    ws_b.send_text(json.dumps({"type": "nearby.unsubscribe"}))
    ws_b.close()
    ws_n.send_text(json.dumps({"type": "nearby.unsubscribe"}))
    ws_s.close()
    ws_n.close()


def run_client_event_checks(nusa, sari, conv_id, ws, ws_sari):
    from datetime import datetime, timedelta, timezone
    body = "WS action " + uuid.uuid4().hex[:8]
    status, payload = http("POST", "/conversations/send", {
        "id": conv_id,
        "type": "text",
        "body": body,
        "client_id": "wsev-" + uuid.uuid4().hex,
    }, token=sari)
    check("action probe send", status == 201, f"status={status} {payload}")
    msg_id = payload.get("data", {}).get("message", {}).get("id")

    ws.send_text(json.dumps({
        "type": "message.ack",
        "data": {"conversation_id": conv_id, "message_id": msg_id},
    }))
    ev = ws_sari.read_json(predicate=lambda e: (
        e.get("type") == "ack" and e.get("data", {}).get("message_id") == msg_id
    ))
    check("ack event via socket", ev["data"].get("user_id") == 1, str(ev)[:200])
    # Centang 2 abu harus persisten di server (bukan cuma event sesaat):
    # dibaca SEBELUM fetch penerima agar fetch tak menutupi bug.
    status, payload = http("GET", f"/conversations/messages?id={conv_id}&after_id={msg_id - 1}", token=sari)
    got = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
    st = next((m.get("status") for m in got if m["id"] == msg_id), None)
    check("ack via socket marks delivered", st == "delivered", f"status={st}")
    status, payload = http("GET", f"/conversations/messages?id={conv_id}&after_id={msg_id - 1}", token=nusa)
    got = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
    exp = next((m.get("expires_at") for m in got if m["id"] == msg_id), None)
    grace_ok = False
    if exp:
        exp_dt = datetime.fromisoformat(exp.replace("Z", "+00:00"))
        grace_ok = exp_dt <= datetime.now(timezone.utc) + timedelta(hours=24, minutes=5)
    check("ack via socket graces", status == 200 and grace_ok, f"expires_at={exp}")

    ws.send_text(json.dumps({
        "type": "message.read",
        "data": {"conversation_id": conv_id, "message_id": msg_id},
    }))
    ev = ws_sari.read_json(predicate=lambda e: (
        e.get("type") == "read" and e.get("data", {}).get("message_id") == msg_id
    ))
    check("read event via socket", ev["data"].get("user_id") == 1, str(ev)[:200])
    status, payload = http("GET", f"/conversations/messages?id={conv_id}&after_id={msg_id - 1}", token=sari)
    got = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
    st = next((m.get("status") for m in got if m["id"] == msg_id), None)
    check("read via socket marks read", st == "read", f"status={st}")
    status, payload = http("GET", f"/conversations/show?id={conv_id}", token=nusa)
    unread = payload.get("data", {}).get("conversation", {}).get("unread_count")
    check("read via socket clears", status == 200 and unread == 0, f"unread={unread}")

    ws.send_text(json.dumps({
        "type": "chat.typing",
        "data": {"conversation_id": conv_id},
    }))
    ev = ws_sari.read_json(predicate=lambda e: (
        e.get("type") == "typing" and e.get("data", {}).get("conversation_id") == conv_id
    ))
    check("typing via socket", ev["data"].get("user_id") == 1 and ev["data"].get("display_name"), str(ev)[:200])

    # Frame rusak: diabaikan, koneksi tetap hidup.
    ws.send_text(json.dumps({"type": "message.ack", "data": {"conversation_id": -5}}))
    ws.send_text(json.dumps({"type": "ping"}))
    pong = ws.read_json(predicate=lambda e: e.get("type") == "pong")
    check("bad frame survives", pong["type"] == "pong", str(pong)[:100])


def nvp_secret():
    """APP_SECRET yang dipakai server: .env backend, else default dev Erlang."""
    here = os.path.dirname(os.path.abspath(__file__))
    try:
        with open(os.path.join(here, "..", ".env"), encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line.startswith("APP_SECRET="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return "dev-secret-change-me-please-32-bytes-min"


def nvp_token(call_id, user_id, secret):
    """Cermin nusa_voice_relay:call_token/2 + Calls::nvpToken."""
    mac = hmac.new(secret.encode(), b"nvp-v1"
                    + struct.pack("!Q", call_id) + struct.pack("!Q", user_id),
                    hashlib.sha256).digest()
    return struct.unpack("!I", mac[:4])[0]


def nvp_packet(call_id, slot, token, seq, ts, payload, flags=0):
    return (struct.pack("!BBIBBIHI", 0x4E, 0x01, call_id, slot, flags,
                        token & 0xFFFFFFFF, seq & 0xFFFF, ts & 0xFFFFFFFF)
            + payload)


def run_nvp_checks(nusa, sari, sari_id, ws, ws_sari):
    secret = nvp_secret()

    # 1. offer via WS -> call.prepared (caller) + call_offer (callee).
    key_ct = base64.urlsafe_b64encode(os.urandom(48)).decode().rstrip("=")
    salt = base64.urlsafe_b64encode(os.urandom(4)).decode().rstrip("=")
    codecs = ["opus", "amr-wb"]
    ws.send_text(json.dumps({"type": "call.offer", "data": {
        "to_user_id": sari_id, "codecs": codecs, "key_ct": key_ct,
        "salt": salt, "kind": "audio"}}))
    prep = ws.read_json(predicate=lambda e: e.get("type") in ("call.prepared", "call.error"))
    check("nvp offer prepared", prep.get("type") == "call.prepared", str(prep)[:200])
    if prep.get("type") != "call.prepared":
        return
    nvp_id = prep["data"]["call_id"]
    check("nvp caller slot 0", prep["data"].get("slot") == 0, str(prep["data"])[:200])
    relay_port = prep["data"].get("relay_port", 8092)
    offer_ev = ws_sari.read_json(predicate=lambda e: (
        e.get("type") == "call_offer"
        and e.get("data", {}).get("call", {}).get("id") == nvp_id))
    got = offer_ev.get("data", {})
    check("nvp offer intact", got.get("codecs") == codecs
          and got.get("key_ct") == key_ct and got.get("salt") == salt,
          str(offer_ev)[:200])

    # 2. answer via WS -> call.prepared (callee) + call_answer (caller).
    ws_sari.send_text(json.dumps({"type": "call.answer", "data": {
        "call_id": nvp_id, "accept": True, "codec": "opus"}}))
    prep_s = ws_sari.read_json(predicate=lambda e: e.get("type") in ("call.prepared", "call.error"))
    check("nvp answer prepared", prep_s.get("type") == "call.prepared", str(prep_s)[:200])
    check("nvp callee slot 1", prep_s.get("data", {}).get("slot") == 1, str(prep_s)[:200])
    ans_ev = ws.read_json(predicate=lambda e: (
        e.get("type") == "call_answer"
        and e.get("data", {}).get("call", {}).get("id") == nvp_id))
    check("nvp answer codec", ans_ev.get("data", {}).get("codec") == "opus", str(ans_ev)[:200])

    # 3. Token silang 3 implementasi: Erlang == PHP == python.
    tok_erl_nusa = prep["data"]["token"]
    tok_erl_sari = prep_s["data"]["token"]
    _, me_n = http("GET", "/users/me", token=nusa)
    nusa_id = me_n["data"]["user"]["id"]
    st, pay = http("POST", "/calls/nvp_join", {"call_id": nvp_id}, token=nusa)
    php_nusa = pay.get("data", {}) if pay.get("ok") else {}
    st2, pay2 = http("POST", "/calls/nvp_join", {"call_id": nvp_id}, token=sari)
    php_sari = pay2.get("data", {}) if pay2.get("ok") else {}
    check("nvp php join ok", st == 200 and st2 == 200, f"{st} {st2} {pay} {pay2}"[:200])
    check("nvp token caller xxx", nvp_token(nvp_id, nusa_id, secret) == tok_erl_nusa == php_nusa.get("token"),
          f"erl={tok_erl_nusa} php={php_nusa.get('token')}")
    check("nvp token callee xxx", nvp_token(nvp_id, sari_id, secret) == tok_erl_sari == php_sari.get("token"),
          f"erl={tok_erl_sari} php={php_sari.get('token')}")
    check("nvp php slots", php_nusa.get("slot") == 0 and php_sari.get("slot") == 1,
          f"{php_nusa.get('slot')} {php_sari.get('slot')}")

    # 4. Relay UDP: forward dua arah byte-identik (buta media).
    host = WS_URL.split("://", 1)[1].split("/", 1)[0].split(":")[0]
    sock_a = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock_b = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock_a.bind(("127.0.0.1", 0))
    sock_b.bind(("127.0.0.1", 0))
    sock_a.settimeout(5)
    sock_b.settimeout(5)
    tag_a = os.urandom(16) + b"A" * 20  # 16B tag + 20B ct
    tag_b = os.urandom(16) + b"B" * 20
    pkt_a = nvp_packet(nvp_id, 0, tok_erl_nusa, 7, 960, tag_a)
    pkt_b = nvp_packet(nvp_id, 1, tok_erl_sari, 3, 480, tag_b)
    check("nvp header 18B", len(pkt_a) - len(tag_a) == 18, f"len={len(pkt_a)}")
    sock_a.sendto(pkt_a, (host, relay_port))
    time.sleep(0.3)  # slot 1 belum dikenal -> belum ada forward
    sock_b.sendto(pkt_b, (host, relay_port))
    fwd_b, _ = sock_a.recvfrom(2048)
    check("nvp forward B->A identik", fwd_b == pkt_b, f"len={len(fwd_b)}")
    sock_a.sendto(pkt_a, (host, relay_port))
    fwd_a, _ = sock_b.recvfrom(2048)
    check("nvp forward A->B identik", fwd_a == pkt_a, f"len={len(fwd_a)}")

    # 5. Token salah -> drop; sampah -> relay tetap hidup.
    bad = nvp_packet(nvp_id, 0, tok_erl_nusa ^ 1, 8, 1020, tag_a)
    sock_a.sendto(bad, (host, relay_port))
    sock_b.settimeout(1)
    try:
        stray, _ = sock_b.recvfrom(2048)
        check("nvp bad token drop", False, f"lolos {len(stray)}B")
    except socket.timeout:
        check("nvp bad token drop", True)
    finally:
        sock_b.settimeout(5)
    sock_a.sendto(b"\x00\x01sampah", (host, relay_port))
    ws.send_text(json.dumps({"type": "ping"}))
    pong = ws.read_json(predicate=lambda e: e.get("type") == "pong")
    check("nvp garbage survives", pong["type"] == "pong")

    # 6. Echo: pantul ke pengirim (uji rantai 1 HP).
    ws.send_text(json.dumps({"type": "call.echo", "data": {"call_id": nvp_id, "on": True}}))
    time.sleep(0.2)
    pkt_e = nvp_packet(nvp_id, 0, tok_erl_nusa, 9, 1920, tag_a)
    sock_a.sendto(pkt_e, (host, relay_port))
    echo, _ = sock_a.recvfrom(2048)
    check("nvp echo identik", echo == pkt_e, f"len={len(echo)}")
    ws.send_text(json.dumps({"type": "call.echo", "data": {"call_id": nvp_id, "on": False}}))

    # 7. Budget framing profil hemat: 60 ms opus ~105B + tag 16 + header 18.
    frame = 18 + 16 + 105
    kbps = frame * 8 / 0.06 / 1000
    check("nvp budget hemat <20kbps", kbps < 20, f"{frame}B -> {kbps:.1f} kbps")

    # 8. End via WS -> call_end ke peer.
    ws.send_text(json.dumps({"type": "call.end", "data": {"call_id": nvp_id}}))
    end_ev = ws_sari.read_json(predicate=lambda e: (
        e.get("type") == "call_end" and e.get("data", {}).get("call_id") == nvp_id))
    check("nvp end event", end_ev["data"].get("reason") == "hangup", str(end_ev)[:200])
    sock_a.close()
    sock_b.close()


def finish():
    print()
    print(f"{checks - len(failures)}/{checks} checks passed")
    if failures:
        print("Failures:")
        for failure in failures:
            print(" -", failure)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
