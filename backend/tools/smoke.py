#!/usr/bin/env python3
"""End-to-end API smoke test for NusaChat.

Usage:
    python3 tools/smoke.py [base_url]

The script assumes the demo seed has been run (nusa/sari, password123).
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("NUSACHAT_API", "http://127.0.0.1:8080/api/v1")).rstrip("/")
PASSWORD = "password123"
failures = []
checks = 0


def request(method, path, data=None, token=None, files=None):
    url = BASE + path
    headers = {"Accept": "application/json"}
    body = None
    if files is not None:
        boundary = "----nusachat" + uuid.uuid4().hex
        parts = []
        for name, (filename, content, mime) in files.items():
            parts.append(f"--{boundary}\r\n".encode())
            parts.append(f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode())
            parts.append(f"Content-Type: {mime}\r\n\r\n".encode())
            parts.append(content)
            parts.append(b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        body = b"".join(parts)
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif data is not None:
        body = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            payload = json.loads(resp.read().decode())
            return resp.status, payload
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"raw": raw}
        return e.code, payload


def check(label, condition, detail=""):
    global checks
    checks += 1
    if condition:
        print(f"  ok  {label}")
    else:
        failures.append(f"{label} {detail}")
        print(f" FAIL {label} {detail}")


def login(identifier):
    status, payload = request("POST", "/auth/login", {"login": identifier, "password": PASSWORD, "device": "smoke"})
    check(f"login {identifier}", status == 200 and payload.get("ok"), f"status={status} {payload}")
    return payload.get("data", {}).get("auth", {}).get("access_token")


def main():
    print(f"NusaChat smoke test -> {BASE}")

    status, payload = request("GET", "/config")
    check("config", status == 200 and payload.get("ok") and payload["data"]["app"]["name"] == "NusaChat", f"status={status}")

    status, payload = request("GET", "/config/ping")
    check("ping", status == 200 and payload.get("data", {}).get("pong") is True, f"status={status}")

    nusa = login("nusa")
    sari = login("sari")
    if not nusa or not sari:
        print("Cannot continue without tokens.")
        return finish()

    status, payload = request("GET", "/users/me", token=nusa)
    check("me", status == 200 and payload["data"]["user"]["username"] == "nusa", f"status={status} {payload}")

    status, payload = request("GET", "/friends", token=nusa)
    check("friends list", status == 200 and len(payload["data"]["friends"]) >= 2, f"status={status}")

    status, payload = request("GET", "/conversations", token=nusa)
    conversations = payload.get("data", {}).get("conversations", []) if payload.get("ok") else []
    check("conversations", status == 200 and len(conversations) >= 2, f"status={status} {payload}")
    conv_id = conversations[0]["id"] if conversations else None

    if conv_id:
        status, payload = request("GET", f"/conversations/messages?id={conv_id}", token=nusa)
        check("messages", status == 200 and len(payload["data"]["messages"]) >= 1, f"status={status}")

        client_id = "smoke-" + uuid.uuid4().hex
        status, payload = request("POST", "/conversations/send", {
            "id": conv_id,
            "type": "text",
            "body": "Pesan smoke test " + time.strftime("%H:%M:%S"),
            "client_id": client_id,
        }, token=nusa)
        check("send message", status == 201 and payload["data"]["message"]["body"].startswith("Pesan smoke"), f"status={status} {payload}")

        status, payload = request("POST", "/conversations/send", {
            "id": conv_id,
            "type": "text",
            "body": "duplicate should not create",
            "client_id": client_id,
        }, token=nusa)
        check("idempotent send", status == 200 and payload["data"]["duplicate"] is True, f"status={status} {payload}")

    status, payload = request("GET", "/nearby?lat=-6.2&lng=106.816&radius=25", token=nusa)
    check("nearby", status == 200 and len(payload["data"]["users"]) >= 1, f"status={status} {payload}")

    status, payload = request("POST", "/moments/create", {"body": "Smoke test moment " + uuid.uuid4().hex[:6]}, token=nusa)
    check("create moment", status == 201 and payload["data"]["moment"]["id"], f"status={status} {payload}")
    moment_id = payload.get("data", {}).get("moment", {}).get("id")
    if moment_id:
        status, payload = request("POST", "/moments/like", {"id": moment_id}, token=sari)
        check("like moment", status == 200 and payload["data"]["liked"] is True, f"status={status} {payload}")
        status, payload = request("POST", "/moments/comment", {"id": moment_id, "body": "Keren!"}, token=sari)
        check("comment moment", status == 201, f"status={status} {payload}")
        status, payload = request("GET", "/moments", token=nusa)
        check("moments feed", status == 200 and any(m["id"] == moment_id for m in payload["data"]["moments"]), f"status={status}")

    # Tiny valid PNG (1x1).
    png = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z0ZkAAAAASUVORK5CYII="
    )
    status, payload = request("POST", "/uploads", token=nusa, files={"file": ("pixel.png", png, "image/png")})
    check("upload", status == 201 and payload["data"]["url"].startswith("http"), f"status={status} {payload}")
    if status == 201:
        url = payload["data"]["url"]
        try:
            with urllib.request.urlopen(url, timeout=10) as resp:
                check("upload served", resp.status == 200 and resp.read() == png, f"status={resp.status}")
        except Exception as exc:  # noqa: BLE001
            check("upload served", False, str(exc))

    # Registration + friend request + accept + direct conversation.
    suffix = uuid.uuid4().hex[:6]
    username = f"smoke{suffix}"
    status, payload = request("POST", "/auth/register", {
        "username": username,
        "password": "password123",
        "display_name": "Smoke " + suffix,
        "device": "smoke",
    })
    check("register", status == 201 and payload["data"]["auth"]["access_token"], f"status={status} {payload}")
    new_token = payload.get("data", {}).get("auth", {}).get("access_token")
    new_id = payload.get("data", {}).get("user", {}).get("id")

    if new_token:
        # nusa adds the new user, new user accepts.
        status, payload = request("POST", "/friends/send", {"user_id": 1}, token=new_token)
        check("friend request", status in (200, 201), f"status={status} {payload}")
        request_id = payload.get("data", {}).get("request", {}).get("request_id")
        status, payload = request("GET", "/friends/requests", token=nusa)
        check("nusa sees request", status == 200 and any(r["request_id"] == request_id for r in payload["data"]["incoming"]), f"status={status} {payload}")
        if request_id:
            status, payload = request("POST", "/friends/respond", {"request_id": request_id, "action": "accept"}, token=nusa)
            check("accept friend", status == 200 and payload["data"]["status"] == "accepted", f"status={status} {payload}")

        status, payload = request("POST", "/conversations/create_direct", {"user_id": new_id}, token=nusa)
        check("create direct", status in (200, 201) and payload["data"]["conversation"]["peer"]["id"] == new_id, f"status={status} {payload}")
        new_conv = payload.get("data", {}).get("conversation", {}).get("id")
        if new_conv:
            status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": "Halo dari smoke test"}, token=nusa)
            check("send to new user", status == 201, f"status={status} {payload}")
            status, payload = request("GET", f"/conversations/messages?id={new_conv}", token=new_token)
            check("new user receives", status == 200 and len(payload["data"]["messages"]) == 1, f"status={status} {payload}")

        status, payload = request("POST", "/auth/logout", {}, token=new_token)
        check("logout", status == 200, f"status={status} {payload}")
        status, payload = request("GET", "/users/me", token=new_token)
        check("token revoked", status == 401, f"status={status} {payload}")

    return finish()


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
