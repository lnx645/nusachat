#!/usr/bin/env python3
"""End-to-end test for the NusaChat WebSocket gateway.

Usage:
    python3 tools/ws_smoke.py [api_base] [ws_url]

Requires the API (PHP built-in server) and tools/ws-server.php to be running.
"""
import base64
import json
import os
import socket
import struct
import sys
import time
import urllib.request
import uuid

API = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080/api/v1").rstrip("/")
WS_URL = sys.argv[2] if len(sys.argv) > 2 else "ws://127.0.0.1:8090"
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

    # Live nearby via Erlang (no PHP polling): location.update answers
    # nearby.result, later updates push nearby.changed to subscribers.
    # Requires the Erlang gateway (8091); skipped on the PHP fallback.
    if "8091" in WS_URL:
        run_nearby_live_checks(nusa, sari)

    ws_sari.close()
    ws.close()
    return finish()


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
