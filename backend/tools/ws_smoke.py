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

    ws.close()
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
