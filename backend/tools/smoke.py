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

    status, payload = request("POST", "/auth/google", {"id_token": "header.payload.sig"})
    err = (payload.get("error") or {}) if isinstance(payload, dict) else {}
    check(
        "google rejects malformed token",
        status == 401 and err.get("code") == "invalid_google_token",
        f"status={status} {payload}",
    )
    status, payload = request("POST", "/auth/google", {})
    err = (payload.get("error") or {}) if isinstance(payload, dict) else {}
    check(
        "google requires id_token",
        status == 422 and err.get("code") == "validation_error",
        f"status={status} {payload}",
    )

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
        status, payload = request("POST", "/moments/create", {"body": "Momen video", "media": ["https://example.com/v.mp4"]}, token=nusa)
        check("video moment", status == 201 and payload["data"]["moment"]["media"] == ["https://example.com/v.mp4"], f"status={status} {payload}")
        status, payload = request("POST", "/moments/reshare", {"id": moment_id}, token=sari)
        reshare_ok = status == 201 and payload["data"]["moment"]["reshared"] and payload["data"]["moment"]["reshared"]["moment_id"] == moment_id
        check("reshare moment", reshare_ok, f"status={status} {payload}")
        status, payload = request("POST", "/moments/reshare", {"id": 999999}, token=sari)
        check("reshare unknown", status == 404, f"status={status} {payload}")

        status, payload = request("POST", "/moments/story_create", {"body": "Story smoke"}, token=nusa)
        check("story create", status == 201 and payload["data"]["story"]["expires_at"], f"status={status} {payload}")
        story_id = payload.get("data", {}).get("story", {}).get("id")
        if story_id:
            status, payload = request("GET", "/moments/stories", token=sari)
            check("stories feed", status == 200 and any(s["id"] == story_id for s in payload["data"]["stories"]), f"status={status}")
            status, payload = request("POST", "/moments/story_delete", {"id": story_id}, token=sari)
            check("story delete foreign", status == 404, f"status={status} {payload}")
            status, payload = request("POST", "/moments/story_delete", {"id": story_id}, token=nusa)
            check("story delete", status == 200, f"status={status} {payload}")
            status, payload = request("GET", "/moments/stories", token=sari)
            check("story gone", status == 200 and all(s["id"] != story_id for s in payload["data"]["stories"]), f"status={status}")

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
            status, payload = request("GET", f"/conversations/show?id={new_conv}", token=nusa)
            member_ids = [m["id"] for m in payload.get("data", {}).get("conversation", {}).get("members", [])] if payload.get("ok") else []
            check("direct show has members", status == 200 and set(member_ids) == {1, new_id}, f"status={status} {payload}")
            wrapped = base64.b64encode(b"k" * 32).decode()
            status, payload = request("POST", "/conversations/set_keys", {"id": new_conv, "keys": [{"user_id": new_id, "wrapped_key": wrapped}]}, token=nusa)
            check("set_keys stored", status == 201 and payload.get("data", {}).get("stored") == 1, f"status={status} {payload}")
            status, payload = request("GET", f"/conversations/keys?id={new_conv}", token=new_token)
            check("keys round-trip", status == 200 and payload.get("data", {}).get("my_key") == wrapped, f"status={status} {payload}")
            # Multi-device: blob per device_id, bukan per user.
            wrapped_b = base64.b64encode(b"b" * 32).decode()
            status, payload = request("POST", "/conversations/set_keys", {"id": new_conv, "keys": [{"user_id": new_id, "device_id": "dev-B", "wrapped_key": wrapped_b}]}, token=nusa)
            check("set_keys per device", status == 201 and payload.get("data", {}).get("stored") == 1, f"status={status} {payload}")
            status, payload = request("GET", f"/conversations/keys?id={new_conv}&device_id=dev-B", token=new_token)
            check("keys per device round-trip", status == 200 and payload.get("data", {}).get("my_key") == wrapped_b, f"status={status} {payload}")
            status, payload = request("GET", f"/conversations/keys?id={new_conv}&device_id=dev-C", token=new_token)
            check("keys unknown device empty", status == 200 and payload.get("data", {}).get("my_key") is None, f"status={status} {payload}")
            devs = payload.get("data", {}).get("devices", [])
            check("keys devices listed", any(d.get("device_id") == "dev-B" for d in devs), f"status={status} {payload}")

            status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": f"Halo @{username} apa kabar"}, token=nusa)
            mention_ok = status == 201 and payload["data"]["message"]["mentions"] == [new_id]
            check("mention resolved", mention_ok, f"status={status} {payload}")
            mention_msg = payload.get("data", {}).get("message", {}).get("id") if status == 201 else None

            if mention_msg:
                status, payload = request("POST", "/messages/edit", {"message_id": mention_msg, "body": "Halo, apa kabar?"}, token=nusa)
                check("edit own message", status == 200 and payload["data"]["message"]["body"] == "Halo, apa kabar?" and payload["data"]["message"]["edited_at"], f"status={status} {payload}")
                status, payload = request("POST", "/messages/edit", {"message_id": mention_msg, "body": "diubah orang lain"}, token=new_token)
                check("edit others forbidden", status == 403, f"status={status} {payload}")

                status, payload = request("POST", "/messages/react", {"message_id": mention_msg, "emoji": "👍"}, token=new_token)
                check("react added", status == 200 and payload["data"]["action"] == "added", f"status={status} {payload}")
                status, payload = request("POST", "/messages/react", {"message_id": mention_msg, "emoji": "👍"}, token=new_token)
                check("react toggled off", status == 200 and payload["data"]["action"] == "removed", f"status={status} {payload}")
                status, payload = request("POST", "/messages/react", {"message_id": mention_msg, "emoji": "❤️"}, token=nusa)
                react_ok = status == 200 and any(r["emoji"] == "❤️" and r["count"] == 1 for r in payload["data"]["reactions"])
                check("react summary", react_ok, f"status={status} {payload}")

                status, payload = request("POST", "/conversations/forward", {"message_id": mention_msg, "to_id": new_conv}, token=nusa)
                fwd_ok = status == 201 and payload["data"]["message"]["forwarded"] is True
                check("forward message", fwd_ok, f"status={status} {payload}")

                status, payload = request("GET", f"/conversations/search?id={new_conv}&q=Halo", token=new_token)
                check("search messages", status == 200 and any(m["id"] == mention_msg for m in payload["data"]["messages"]), f"status={status} {payload}")
                status, payload = request("GET", f"/conversations/search?id={new_conv}&q=x", token=new_token)
                check("search too short", status == 422, f"status={status} {payload}")

                status, payload = request("POST", "/conversations/pin", {"id": new_conv, "message_id": mention_msg}, token=nusa)
                check("pin message", status == 200 and any(p["message"]["id"] == mention_msg for p in payload["data"]["pins"]), f"status={status} {payload}")
                status, payload = request("GET", f"/conversations/pins?id={new_conv}", token=new_token)
                check("list pins", status == 200 and any(p["message"]["id"] == mention_msg for p in payload["data"]["pins"]), f"status={status} {payload}")
                status, payload = request("POST", "/conversations/unpin", {"id": new_conv, "message_id": mention_msg}, token=nusa)
                check("unpin message", status == 200 and payload["data"]["pins"] == [], f"status={status} {payload}")

            status, payload = request("POST", "/conversations/archive", {"id": new_conv, "archived": True}, token=nusa)
            check("archive conversation", status == 200 and payload["data"]["archived"] is True, f"status={status} {payload}")
            status, payload = request("GET", "/conversations", token=nusa)
            check("archived hidden", status == 200 and all(c["id"] != new_conv for c in payload["data"]["conversations"]), f"status={status}")
            status, payload = request("GET", "/conversations?archived=1", token=nusa)
            check("archived listed", status == 200 and any(c["id"] == new_conv for c in payload["data"]["conversations"]), f"status={status} {payload}")
            status, payload = request("POST", "/conversations/archive", {"id": new_conv, "archived": False}, token=nusa)
            check("unarchive conversation", status == 200 and payload["data"]["archived"] is False, f"status={status} {payload}")

        # Grup: invite link, persetujuan, dan role admin.
        budi = login("budi")
        dewi = login("dewi")
        _, me_budi = request("GET", "/users/me", token=budi)
        budi_id = me_budi["data"]["user"]["id"]
        # Online = socket WS hidup; REST saja (tanpa socket) tetap offline.
        status, payload = request("GET", "/conversations", token=nusa)
        convs = payload.get("data", {}).get("conversations", []) if payload.get("ok") else []
        budi_conv = next((c for c in convs if c.get("peer", {}).get("username") == "budi"), None)
        check("peer offline without socket", budi_conv is not None and budi_conv["peer"].get("online") is False,
              f"peer={budi_conv['peer'] if budi_conv else None}")
        status, payload = request("POST", "/conversations/create_group", {"title": "Smoke Group", "member_ids": [2]}, token=nusa)
        check("create group", status == 201, f"status={status} {payload}")
        group_id = payload.get("data", {}).get("conversation", {}).get("id")
        if group_id:
            status, payload = request("POST", "/conversations/invite", {"id": group_id}, token=nusa)
            token_ok = status == 200 and len(payload["data"]["invite_token"]) >= 16
            check("invite link", token_ok, f"status={status} {payload}")
            invite_token = payload.get("data", {}).get("invite_token", "")
            status, payload = request("POST", "/conversations/invite", {"id": group_id, "rotate": True}, token=nusa)
            rotated = status == 200 and payload["data"]["invite_token"] != invite_token
            check("rotate invite", rotated, f"status={status} {payload}")
            invite_token = payload.get("data", {}).get("invite_token", invite_token)
            status, payload = request("POST", "/conversations/join", {"token": "nusachat://group/" + invite_token}, token=budi)
            check("join via link", status == 201 and payload["data"]["status"] == "joined", f"status={status} {payload}")

            # Centang grup: 1 pembaca -> delivered; semua penerima -> read.
            status, payload = request("POST", "/conversations/send", {"id": group_id, "type": "text", "body": f"GrupRead-{suffix}"}, token=nusa)
            grp_msg = payload.get("data", {}).get("message", {}).get("id")
            status, payload = request("POST", "/conversations/read", {"id": group_id, "message_id": grp_msg}, token=sari)
            status, payload = request("GET", f"/conversations/messages?id={group_id}&after_id={grp_msg - 1}", token=nusa)
            got = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
            st = next((m.get("status") for m in got if m["id"] == grp_msg), None)
            check("group read waits for all", st == "delivered", f"status={st}")
            status, payload = request("POST", "/conversations/read", {"id": group_id, "message_id": grp_msg}, token=budi)
            status, payload = request("GET", f"/conversations/messages?id={group_id}&after_id={grp_msg - 1}", token=nusa)
            got = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
            st = next((m.get("status") for m in got if m["id"] == grp_msg), None)
            check("group read all", st == "read", f"status={st}")

            status, payload = request("POST", "/conversations/update_group_settings", {"id": group_id, "approve_new_members": True}, token=nusa)
            check("enable approval", status == 200 and payload["data"]["settings"]["approve_new_members"] is True, f"status={status} {payload}")
            status, payload = request("POST", "/conversations/join", {"token": invite_token}, token=dewi)
            check("join pending", status == 202 and payload["data"]["status"] == "pending", f"status={status} {payload}")
            req_id = payload.get("data", {}).get("request_id")
            status, payload = request("GET", f"/conversations/join_requests?id={group_id}", token=nusa)
            check("list join requests", status == 200 and any(r["request_id"] == req_id for r in payload["data"]["requests"]), f"status={status} {payload}")
            status, payload = request("POST", "/conversations/review_request", {"request_id": req_id, "action": "approve"}, token=dewi)
            check("review by non-admin forbidden", status == 403, f"status={status} {payload}")
            status, payload = request("POST", "/conversations/review_request", {"request_id": req_id, "action": "approve"}, token=nusa)
            check("approve join", status == 200 and payload["data"]["status"] == "approved", f"status={status} {payload}")
            status, payload = request("GET", "/conversations", token=dewi)
            check("approved user is member", status == 200 and any(c["id"] == group_id for c in payload["data"]["conversations"]), f"status={status} {payload}")

            status, payload = request("POST", "/conversations/set_role", {"id": group_id, "user_id": 2, "role": "admin"}, token=nusa)
            check("promote admin", status == 200 and payload["data"]["role"] == "admin", f"status={status} {payload}")
            status, payload = request("POST", "/conversations/remove_member", {"id": group_id, "user_id": budi_id}, token=sari)
            check("admin removes member", status == 200, f"status={status} {payload}")
            status, payload = request("POST", "/conversations/remove_member", {"id": group_id, "user_id": 1}, token=sari)
            check("admin cannot remove owner", status == 403, f"status={status} {payload}")
            status, payload = request("POST", "/conversations/set_role", {"id": group_id, "user_id": 2, "role": "member"}, token=sari)
            check("admin cannot set roles", status == 403, f"status={status} {payload}")
            status, payload = request("POST", "/conversations/set_role", {"id": group_id, "user_id": 2, "role": "member"}, token=nusa)
            check("demote admin", status == 200, f"status={status} {payload}")

            status, payload = request("POST", "/conversations/update_group_settings", {"id": group_id, "invite_link_enabled": False}, token=nusa)
            check("disable invite", status == 200, f"status={status} {payload}")
            status, payload = request("POST", "/conversations/join", {"token": invite_token}, token=budi)
            check("join disabled link", status == 403, f"status={status} {payload}")

        # Panggilan: voice + video 1-1 + room grup.
        fake_sdp = "v=0\r\no=smoke 1 1 IN IP4 127.0.0.1\r\ns=smoke\r\nt=0 0\r\n"
        status, payload = request("POST", "/calls/offer", {"to_user_id": 2, "sdp": fake_sdp, "kind": "video"}, token=nusa)
        video_call = payload.get("data", {}).get("call", {}).get("id") if status == 201 else None
        check("video offer accepted", status == 201 and payload["data"]["call"]["kind"] == "video", f"status={status} {payload}")
        if video_call:
            status, payload = request("POST", "/calls/end", {"call_id": video_call}, token=nusa)
            check("video call cleanup", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/calls/offer", {"to_user_id": 2, "sdp": fake_sdp, "kind": "audio"}, token=nusa)
        check("audio offer", status == 201 and payload["data"]["call"]["kind"] == "audio", f"status={status} {payload}")
        call_id = payload.get("data", {}).get("call", {}).get("id")
        status, payload = request("POST", "/calls/offer", {"to_user_id": 2, "sdp": fake_sdp, "kind": "hologram"}, token=nusa)
        check("invalid kind rejected", status == 422, f"status={status} {payload}")
        if call_id:
            status, payload = request("GET", "/calls/incoming", token=sari)
            check("callee sees incoming", status == 200 and any(c["id"] == call_id for c in payload["data"]["calls"]), f"status={status} {payload}")
            status, payload = request("POST", "/calls/answer", {"call_id": call_id, "sdp": fake_sdp}, token=sari)
            check("answer call", status == 200 and payload["data"]["call"]["status"] == "active", f"status={status} {payload}")
            status, payload = request("POST", "/calls/ice", {"call_id": call_id, "candidate": {"candidate": "a", "sdpMid": "0", "sdpMLineIndex": 0}}, token=nusa)
            check("1-1 ice relay", status == 200 and payload["data"]["sent"] is True, f"status={status} {payload}")
            status, payload = request("POST", "/calls/end", {"call_id": call_id}, token=nusa)
            check("end call", status == 200 and payload["data"]["call"]["status"] == "ended", f"status={status} {payload}")
            status, payload = request("GET", "/calls/ice_servers", token=nusa)
            servers = payload.get("data", {}).get("iceServers", []) if payload.get("ok") else []
            urls = [u for s in servers if isinstance(s, dict) for u in (s.get("urls") or []) if isinstance(u, str)]
            check("ice_servers cloudflare", status == 200 and any("cloudflare" in u for u in urls), f"status={status} {payload}")
            dump = json.dumps(payload)
            check("api token not leaked", "api_token" not in dump and "TURN_API_TOKEN" not in dump, "token leaked in response")

        status, payload = request("POST", "/conversations/create_group", {"title": "Smoke Call Group", "member_ids": [2, budi_id]}, token=nusa)
        check("call group created", status == 201, f"status={status} {payload}")
        call_group = payload.get("data", {}).get("conversation", {}).get("id")
        if call_group:
            status, payload = request("POST", "/calls/room_create", {"conversation_id": call_group, "kind": "audio"}, token=nusa)
            check("room created", status == 201 and payload["data"]["reused"] is False, f"status={status} {payload}")
            room_id = payload.get("data", {}).get("call", {}).get("id")
            status, payload = request("POST", "/calls/room_create", {"conversation_id": call_group}, token=budi)
            check("room reused", status == 200 and payload["data"]["reused"] is True and payload["data"]["call"]["id"] == room_id, f"status={status} {payload}")
            status, payload = request("GET", f"/calls/room_state?call_id={room_id}", token=sari)
            check("room state", status == 200 and any(p["user"]["id"] == 1 for p in payload["data"]["participants"]), f"status={status} {payload}")
            status, payload = request("POST", "/calls/room_join", {"call_id": room_id}, token=sari)
            check("room join", status == 200 and payload["data"]["call"]["status"] == "active", f"status={status} {payload}")
            status, payload = request("POST", "/calls/room_signal", {"call_id": room_id, "to_user_id": 1, "sdp": fake_sdp, "signal": "offer"}, token=sari)
            check("room sdp relay", status == 200 and payload["data"]["sent"] is True, f"status={status} {payload}")
            status, payload = request("POST", "/calls/room_signal", {"call_id": room_id, "to_user_id": 1, "sdp": fake_sdp, "signal": "ring"}, token=sari)
            check("room bad signal rejected", status == 422, f"status={status} {payload}")
            status, payload = request("POST", "/calls/ice", {"call_id": room_id, "candidate": {"candidate": "b", "sdpMid": "0", "sdpMLineIndex": 0}, "to_user_id": 1}, token=sari)
            check("room ice routed", status == 200, f"status={status} {payload}")
            status, payload = request("POST", "/calls/ice", {"call_id": room_id, "candidate": {"candidate": "b"}}, token=sari)
            check("room ice needs target", status == 422, f"status={status} {payload}")
            status, payload = request("POST", "/calls/room_join", {"call_id": room_id}, token=new_token)
            check("non-member cannot join", status == 403, f"status={status} {payload}")
            status, payload = request("POST", "/calls/room_leave", {"call_id": room_id}, token=sari)
            check("leave keeps room", status == 200 and payload["data"]["ended"] is False, f"status={status} {payload}")
            status, payload = request("GET", "/calls/incoming", token=budi)
            check("room listed incoming", status == 200 and any(r["id"] == room_id for r in payload["data"]["rooms"]), f"status={status} {payload}")
            status, payload = request("POST", "/calls/room_leave", {"call_id": room_id}, token=budi)
            check("second leave keeps room", status == 200 and payload["data"]["ended"] is False, f"status={status} {payload}")
            status, payload = request("POST", "/calls/room_leave", {"call_id": room_id}, token=nusa)
            check("last leave ends room", status == 200 and payload["data"]["ended"] is True, f"status={status} {payload}")
            status, payload = request("POST", "/calls/room_join", {"call_id": room_id}, token=sari)
            check("cannot join ended room", status == 422, f"status={status} {payload}")

        # Blokir: nusa memblokir user smoke baru (new_id/new_token masih hidup).
        status, payload = request("POST", "/friends/block", {"user_id": new_id}, token=nusa)
        check("block user", status == 200 and payload["data"]["blocked"] is True, f"status={status} {payload}")
        status, payload = request("GET", "/friends/blocked", token=nusa)
        check("blocked list", status == 200 and any(u["id"] == new_id for u in payload["data"]["users"]), f"status={status} {payload}")
        status, payload = request("POST", "/friends/send", {"user_id": new_id}, token=nusa)
        check("send to blocked fails", status == 403, f"status={status} {payload}")
        status, payload = request("POST", "/friends/send", {"user_id": 1}, token=new_token)
        check("blocked sees nothing", status == 404, f"status={status} {payload}")
        status, payload = request("GET", f"/users/show?id={new_id}", token=nusa)
        check("show blocked hidden", status == 404, f"status={status} {payload}")
        status, payload = request("GET", "/users/show?id=1", token=new_token)
        check("show blocker hidden", status == 404, f"status={status} {payload}")
        status, payload = request("GET", "/users/search?q=" + username, token=nusa)
        check("search hides blocked", status == 200 and all(u["id"] != new_id for u in payload["data"]["users"]), f"status={status} {payload}")
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": "harusnya gagal"}, token=nusa)
        check("send to blocked conv fails", status == 403, f"status={status} {payload}")
        status, payload = request("POST", "/calls/offer", {"to_user_id": new_id, "sdp": fake_sdp}, token=nusa)
        check("call to blocked fails", status == 403, f"status={status} {payload}")
        status, payload = request("POST", "/calls/offer", {"to_user_id": 1, "sdp": fake_sdp}, token=new_token)
        check("blocked caller hidden", status == 404, f"status={status} {payload}")
        status, payload = request("GET", "/friends", token=nusa)
        check("friends flag blocked", status == 200 and any(f["id"] == new_id and f.get("blocked_by_me") is True for f in payload["data"]["friends"]), f"status={status} {payload}")
        status, payload = request("POST", "/friends/unblock", {"user_id": new_id}, token=nusa)
        check("unblock user", status == 200 and payload["data"]["blocked"] is False, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": "Setelah buka blokir"}, token=nusa)
        check("send after unblock", status == 201, f"status={status} {payload}")
        _, me_nusa = request("GET", "/users/me", token=nusa)
        nusa_id = me_nusa["data"]["user"]["id"]
        status, payload = request("POST", "/friends/block", {"user_id": nusa_id}, token=nusa)
        check("cannot block self", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/friends/block", {"user_id": 999999}, token=nusa)
        check("block unknown user", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/friends/unblock", {"user_id": new_id}, token=nusa)
        check("cleanup unblock", status == 200, f"status={status} {payload}")

        # Hapus akun: user khusus yang didaftarkan lalu dihapus.
        del_name = "del" + uuid.uuid4().hex[:8]
        status, payload = request("POST", "/auth/register", {"username": del_name, "password": "secret123", "display_name": "Hapus Saya"})
        check("register deletable", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/auth/login", {"login": del_name, "password": "secret123", "device": "smoke"})
        check("login deletable", status == 200, f"status={status} {payload}")
        del_token = payload.get("data", {}).get("auth", {}).get("access_token")
        status, payload = request("POST", "/users/delete", {"password": "salah"}, token=del_token)
        check("delete wrong password", status == 401, f"status={status} {payload}")
        status, payload = request("POST", "/users/delete", {"password": "secret123"}, token=del_token)
        check("delete account", status == 200 and payload["data"]["deleted"] is True, f"status={status} {payload}")

        # Kunci E2EE: blob opaque round-trip + proteksi member.
        fake_pub = base64.b64encode(b"\x30" + b"A" * 100).decode()
        fake_wrap = base64.b64encode(b"W" * 64).decode()
        status, payload = request("POST", "/users/device_key", {"device_id": "smoke-dev", "public_key": fake_pub}, token=nusa)
        check("publish device key", status == 201, f"status={status} {payload}")
        status, payload = request("GET", "/users/device_keys?user_id=1", token=sari)
        check("read device keys", status == 200 and any(k["public_key"] == fake_pub for k in payload["data"]["keys"]), f"status={status} {payload}")
        status, payload = request("POST", "/users/device_key", {"device_id": "x", "public_key": "bukan-base64!!!"}, token=nusa)
        check("bad public key rejected", status == 422, f"status={status} {payload}")
        # Direktori device E2EE per device (docs/E2EE-PROTOCOL.md).
        sign_pub = base64.b64encode(b"S" * 32).decode()
        dh_pub = base64.b64encode(b"D" * 32).decode()
        status, payload = request("POST", "/devices/register", {"device_id": "smoke-e2ee", "platform": "test", "device_name": "Smoke", "identity_sign_pub": sign_pub, "identity_dh_pub": dh_pub}, token=nusa)
        check("register device", status == 201 and payload["data"]["device"]["device_id"] == "smoke-e2ee", f"status={status} {payload}")
        status, payload = request("POST", "/devices/register", {"device_id": "bad", "identity_sign_pub": "pendek", "identity_dh_pub": dh_pub}, token=nusa)
        check("bad identity key rejected", status == 422, f"status={status} {payload}")
        spk_pub = base64.b64encode(b"P" * 32).decode()
        spk_sig = base64.b64encode(b"G" * 64).decode()
        otpk = base64.b64encode(b"O" * 32).decode()
        status, payload = request("POST", "/devices/prekeys", {"device_id": "smoke-e2ee", "signed_prekey": {"key_id": 1, "public_key": spk_pub, "signature": spk_sig}, "onetime_prekeys": [{"key_id": 1, "public_key": otpk}]}, token=nusa)
        check("upload prekeys", status == 201 and payload["data"]["stored_onetime"] == 1, f"status={status} {payload}")
        status, payload = request("GET", "/devices/bundle?user_id=1&device_id=smoke-e2ee", token=sari)
        bundle = payload.get("data", {}).get("bundle", {}) if status == 200 else {}
        check("fetch bundle", status == 200 and bundle.get("identity_dh_pub") == dh_pub and (bundle.get("onetime_prekey") or {}).get("key_id") == 1, f"status={status} {payload}")
        status, payload = request("GET", "/devices/bundle?user_id=1&device_id=smoke-e2ee", token=sari)
        bundle2 = payload.get("data", {}).get("bundle", {}) if status == 200 else {}
        check("onetime consumed once", status == 200 and bundle2.get("onetime_prekey") is None, f"status={status} {payload}")
        status, payload = request("GET", "/devices/list?user_id=1", token=sari)
        check("list devices", status == 200 and any(d["device_id"] == "smoke-e2ee" for d in payload["data"]["devices"]), f"status={status} {payload}")
        status, payload = request("GET", "/devices/mine", token=nusa)
        check("mine devices", status == 200 and any(d["device_id"] == "smoke-e2ee" for d in payload["data"]["devices"]), f"status={status} {payload}")
        status, payload = request("POST", "/devices/revoke", {"device_id": "smoke-e2ee"}, token=nusa)
        check("revoke device", status == 200, f"status={status} {payload}")
        status, payload = request("GET", "/devices/bundle?user_id=1&device_id=smoke-e2ee", token=sari)
        check("revoked bundle gone", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/set_keys", {"id": new_conv, "keys": [{"user_id": new_id, "wrapped_key": fake_wrap}]}, token=nusa)
        check("store wrapped keys", status == 201 and payload["data"]["stored"] == 1, f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/keys?id={new_conv}", token=new_token)
        check("read my wrapped key", status == 200 and payload["data"]["my_key"] == fake_wrap, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/set_keys", {"id": new_conv, "keys": [{"user_id": 1, "wrapped_key": fake_wrap}]}, token=budi)
        check("non-member cannot set keys", status == 404, f"status={status} {payload}")

        # Dompet: topup, transfer, riwayat, batas.
        status, payload = request("GET", "/wallet", token=nusa)
        nusa_start = payload["data"]["balance"] if status == 200 else 0
        check("wallet balance", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/topup", {"amount": 50000}, token=nusa)
        check("wallet topup", status == 201 and payload["data"]["balance"] == nusa_start + 50000, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/transfer", {"to_user_id": 2, "amount": 10000, "note": "Traktir"}, token=nusa)
        check("wallet transfer", status == 201 and payload["data"]["balance"] == nusa_start + 40000, f"status={status} {payload}")
        status, payload = request("GET", "/wallet", token=sari)
        check("recipient credited", status == 200 and payload["data"]["balance"] >= 10000, f"status={status} {payload}")
        status, payload = request("GET", "/wallet/history", token=nusa)
        hist_ok = status == 200 and any(t["kind"] == "transfer" and t["amount"] == 10000 for t in payload["data"]["transactions"])
        check("wallet history", hist_ok, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/transfer", {"to_user_id": 2, "amount": 9000000}, token=nusa)
        check("overdraft rejected", status == 422 and payload["error"]["code"] == "insufficient_balance", f"status={status} {payload}")
        status, payload = request("POST", "/wallet/transfer", {"to_user_id": 1, "amount": 100}, token=nusa)
        check("self transfer rejected", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/transfer", {"to_user_id": 999999, "amount": 100}, token=nusa)
        check("transfer unknown user", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/topup", {"amount": 0}, token=nusa)
        check("zero topup rejected", status == 422, f"status={status} {payload}")
        status, payload = request("GET", "/users/me", token=del_token)
        check("token dead after delete", status == 401, f"status={status} {payload}")
        status, payload = request("POST", "/auth/login", {"login": del_name, "password": "secret123", "device": "smoke"})
        check("login dead after delete", status in (401, 403), f"status={status} {payload}")

        # Reset password: token sekali pakai + anti enumerasi.
        rst_name = f"rst{suffix}"
        status, payload = request("POST", "/auth/register", {"username": rst_name, "password": "oldpass1", "display_name": "Reset Me"})
        check("register resettable", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/auth/forgot", {"login": "tidakada000"})
        check("forgot unknown hides", status == 200 and payload["data"]["sent"] is True and "reset_token" not in payload["data"], f"status={status} {payload}")
        status, payload = request("POST", "/auth/forgot", {"login": rst_name})
        rst_token = payload.get("data", {}).get("reset_token")
        check("forgot known issues token", status == 200 and bool(rst_token), f"status={status} {payload}")
        status, payload = request("POST", "/auth/reset", {"token": "salah", "new_password": "newpass1"})
        check("reset bad token", status == 401, f"status={status} {payload}")
        status, payload = request("POST", "/auth/reset", {"token": rst_token, "new_password": "x"})
        check("reset short password", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/auth/reset", {"token": rst_token, "new_password": "newpass1"})
        check("reset password", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/auth/login", {"login": rst_name, "password": "oldpass1"})
        check("old password dead", status == 401, f"status={status} {payload}")
        status, payload = request("POST", "/auth/login", {"login": rst_name, "password": "newpass1"})
        check("new password works", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/auth/reset", {"token": rst_token, "new_password": "newpass2"})
        check("reset token single use", status == 401, f"status={status} {payload}")

        # Sesi: daftar + cabut per perangkat.
        status, payload = request("POST", "/auth/register", {"username": f"ssn{suffix}", "password": "secret123", "display_name": "Sesi", "device": "smoke-A"})
        ref_a = payload.get("data", {}).get("auth", {}).get("refresh_token")
        ssn_token = payload.get("data", {}).get("auth", {}).get("access_token")
        status, payload = request("POST", "/auth/login", {"login": f"ssn{suffix}", "password": "secret123", "device": "smoke-B"})
        ref_b = payload.get("data", {}).get("auth", {}).get("refresh_token")
        status, payload = request("GET", "/auth/sessions", token=ssn_token)
        sessions = payload.get("data", {}).get("sessions", []) if payload.get("ok") else []
        check("list sessions", status == 200 and len(sessions) >= 2 and all(s["jti"] and s["device"] for s in sessions), f"status={status} {payload}")
        status, payload = request("POST", "/auth/revoke_session", {"jti": "tidakada"}, token=ssn_token)
        check("revoke unknown session", status == 404, f"status={status} {payload}")
        jti_a = ref_a.split(".")[0]
        status, payload = request("POST", "/auth/revoke_session", {"jti": jti_a}, token=ssn_token)
        check("revoke one session", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/auth/refresh", {"refresh_token": ref_a})
        check("revoked refresh dead", status == 401, f"status={status} {payload}")
        status, payload = request("POST", "/auth/refresh", {"refresh_token": ref_b})
        check("other refresh alive", status == 200 and payload["data"]["auth"]["access_token"], f"status={status} {payload}")
        # Reuse token yang sudah DIROTASI = indikasi curian → seluruh keluarga mati.
        # (Beda dengan retry token yang dicabut eksplisit: itu 401 biasa.)
        ref_b2 = (payload.get("data", {}) or {}).get("auth", {}).get("refresh_token") if status == 200 else None
        status, payload = request("POST", "/auth/refresh", {"refresh_token": ref_b})
        err = (payload.get("error") or {}) if isinstance(payload, dict) else {}
        check("rotated reuse nukes family", status == 401 and err.get("code") == "token_revoked", f"status={status} {payload}")
        if ref_b2:
            status, payload = request("POST", "/auth/refresh", {"refresh_token": ref_b2})
            check("family dead after reuse", status == 401, f"status={status} {payload}")

        # Ganti username.
        status, payload = request("POST", "/auth/register", {"username": f"nama{suffix}", "password": "secret123", "display_name": "Nama"})
        nm_token = payload.get("data", {}).get("auth", {}).get("access_token")
        status, payload = request("POST", "/users/update", {"username": "ab"}, token=nm_token)
        check("username too short", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/users/update", {"username": "admin"}, token=nm_token)
        check("username reserved", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/users/update", {"username": "sari"}, token=nm_token)
        check("username taken", status == 409, f"status={status} {payload}")
        status, payload = request("POST", "/users/update", {"username": f"baru{suffix}"}, token=nm_token)
        check("username changed", status == 200 and payload["data"]["user"]["username"] == f"baru{suffix}", f"status={status} {payload}")
        status, payload = request("POST", "/auth/login", {"login": f"baru{suffix}", "password": "secret123"})
        check("login new username", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/auth/login", {"login": f"nama{suffix}", "password": "secret123"})
        check("old username dead", status == 401, f"status={status} {payload}")

        # Pin chat, tandai belum dibaca, cari global, pagination.
        status, payload = request("POST", "/conversations/pin_chat", {"id": new_conv}, token=nusa)
        check("pin chat", status == 200 and payload["data"]["pinned"] is True, f"status={status} {payload}")
        status, payload = request("GET", "/conversations", token=nusa)
        convs = payload.get("data", {}).get("conversations", []) if payload.get("ok") else []
        check("pinned first", status == 200 and convs and convs[0]["id"] == new_conv and convs[0]["pinned"] is True, f"status={status}")
        status, payload = request("POST", "/conversations/pin_chat", {"id": new_conv, "pinned": False}, token=nusa)
        check("unpin chat", status == 200 and payload["data"]["pinned"] is False, f"status={status} {payload}")
        probe = f"TandaiBelumDibaca-{suffix}"
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": probe}, token=new_token)
        probe_id = payload.get("data", {}).get("message", {}).get("id")
        status, payload = request("POST", "/conversations/read", {"id": new_conv, "message_id": probe_id}, token=nusa)
        check("read probe", status == 200, f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/show?id={new_conv}", token=nusa)
        check("unread zero", status == 200 and payload["data"]["conversation"]["unread_count"] == 0, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/mark_unread", {"id": new_conv, "message_id": probe_id}, token=nusa)
        check("mark unread", status == 200 and payload["data"]["unread_count"] >= 1, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/read", {"id": new_conv, "message_id": probe_id}, token=nusa)
        check("read again cleanup", status == 200, f"status={status} {payload}")
        # Tandai-belum-dibaca tak boleh melompat MAJU: pesan berikutnya
        # harus tetap menaikkan badge.
        status, payload = request("POST", "/conversations/mark_unread", {"id": new_conv, "message_id": 999999999}, token=nusa)
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": f"SetelahMark-{suffix}"}, token=new_token)
        status, payload = request("GET", f"/conversations/show?id={new_conv}", token=nusa)
        check("mark unread never jumps forward",
              status == 200 and payload["data"]["conversation"]["unread_count"] >= 1,
              f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/search_all?q={probe}", token=nusa)
        found = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
        check("search all chats", status == 200 and any(m["id"] == probe_id and m["conversation_id"] == new_conv for m in found), f"status={status} {payload}")
        status, payload = request("GET", "/conversations/search_all?q=x", token=nusa)
        check("search all too short", status == 422, f"status={status} {payload}")
        status, payload = request("GET", "/conversations?limit=1", token=nusa)
        page_ok = status == 200 and len(payload["data"]["conversations"]) == 1 and payload["data"]["has_more"] is True and payload["data"]["next_before"]
        check("conversations pagination", page_ok, f"status={status} {payload}")
        if page_ok:
            before = payload["data"]["next_before"]
            status, payload = request("GET", f"/conversations?limit=50&before={before}", token=nusa)
            check("conversations next page", status == 200, f"status={status} {payload}")

        # Bintang + hapus-untuk-saya + stiker.
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": f"Bintang-{suffix}"}, token=nusa)
        star_msg = payload.get("data", {}).get("message", {}).get("id")
        status, payload = request("POST", "/messages/star", {"message_id": star_msg}, token=nusa)
        check("star message", status == 201, f"status={status} {payload}")
        status, payload = request("GET", "/messages/starred", token=nusa)
        check("starred list", status == 200 and any(m["id"] == star_msg for m in payload["data"]["messages"]), f"status={status} {payload}")
        status, payload = request("POST", "/messages/unstar", {"message_id": star_msg}, token=nusa)
        check("unstar message", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/messages/hide", {"message_id": star_msg}, token=nusa)
        check("hide message", status == 200, f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/messages?id={new_conv}&limit=100", token=nusa)
        check("hidden from me", status == 200 and all(m["id"] != star_msg for m in payload["data"]["messages"]), f"status={status}")
        status, payload = request("GET", f"/conversations/messages?id={new_conv}&limit=100", token=new_token)
        check("visible to peer", status == 200 and any(m["id"] == star_msg for m in payload["data"]["messages"]), f"status={status}")
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "sticker", "body": "🦁"}, token=nusa)
        check("send sticker", status == 201 and payload["data"]["message"]["type"] == "sticker", f"status={status} {payload}")
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "sticker", "body": ""}, token=nusa)
        check("empty sticker rejected", status == 422, f"status={status} {payload}")

        # Relay ack: pesan di-ack peer → masa tenggang 24 jam, tetap bisa dibaca/dibalas.
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": f"AckRelay-{suffix}"}, token=nusa)
        ack_msg = payload.get("data", {}).get("message", {}).get("id")
        check("relay send", status == 201 and ack_msg, f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/messages?id={new_conv}&after_id={ack_msg - 1}", token=new_token)
        got = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
        check("relay fetch", status == 200 and any(m["id"] == ack_msg for m in got), f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/messages?id={new_conv}&after_id={ack_msg - 1}", token=nusa)
        got = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
        st = next((m.get("status") for m in got if m["id"] == ack_msg), None)
        check("fetch marks delivered", st == "delivered", f"status={st}")
        status, payload = request("POST", "/conversations/ack", {"id": new_conv, "message_id": ack_msg}, token=new_token)
        check("relay ack", status == 200 and payload.get("ok"), f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/messages?id={new_conv}&after_id={ack_msg - 1}", token=nusa)
        got = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
        exp = next((m.get("expires_at") for m in got if m["id"] == ack_msg), None)
        check("relay grace kept", status == 200 and exp is not None, f"status={status} {payload}")
        if exp:
            from datetime import datetime, timedelta, timezone
            exp_dt = datetime.fromisoformat(exp.replace("Z", "+00:00"))
            check("relay grace 24h", exp_dt <= datetime.now(timezone.utc) + timedelta(hours=24, minutes=5), f"expires_at={exp}")
        status, payload = request("POST", "/conversations/read", {"id": new_conv, "message_id": ack_msg}, token=nusa)
        check("read after ack", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/read", {"id": new_conv, "message_id": ack_msg}, token=new_token)
        status, payload = request("GET", f"/conversations/messages?id={new_conv}&after_id={ack_msg - 1}", token=nusa)
        got = payload.get("data", {}).get("messages", []) if payload.get("ok") else []
        st = next((m.get("status") for m in got if m["id"] == ack_msg), None)
        check("peer read marks read", st == "read", f"status={st}")
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": f"Balas-{suffix}", "reply_to_id": ack_msg}, token=new_token)
        check("reply after ack", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/ack", {"id": new_conv, "message_id": 999999999}, token=nusa)
        check("ack unknown idempotent", status == 200, f"status={status} {payload}")

        # Amplop E2EE v2: server buram (passthrough utuh) + batas 8192 + silent.
        env = '{"v":2,"kind":"pairwise","from":"dev_x","parts":{}}'
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": env}, token=nusa)
        check("envelope passthrough", status == 201 and payload["data"]["message"]["body"] == env, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": "L" * 5000}, token=nusa)
        check("long body 5000", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": "skdm-ish", "silent": True}, token=nusa)
        check("silent send", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": "L" * 8193}, token=nusa)
        check("body 8193 rejected", status == 422, f"status={status} {payload}")

        # Idempotency + toleransi relay + has_more pra-filter.
        dup_cid = f"dup-{suffix}"
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": "sekali saja", "client_id": dup_cid}, token=nusa)
        first_id = payload.get("data", {}).get("message", {}).get("id")
        check("send with client_id", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/send", {"id": new_conv, "type": "text", "body": "beda isi", "client_id": dup_cid}, token=nusa)
        check("duplicate client_id", status == 200 and payload["data"]["duplicate"] is True and payload["data"]["message"]["id"] == first_id, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/mark_unread", {"id": new_conv, "message_id": 999999999}, token=nusa)
        check("mark unread unknown", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/mark_unread", {"id": new_conv, "message_id": first_id}, token=nusa)
        check("unread restores marker", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/messages/hide", {"message_id": 999999999}, token=nusa)
        check("hide unknown", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/messages/star", {"message_id": 999999998}, token=nusa)
        check("star unknown", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/messages/hide", {"message_id": first_id}, token=nusa)
        check("hide newest", status == 200, f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/messages?id={new_conv}&limit=1", token=nusa)
        page = payload.get("data", {}) if payload.get("ok") else {}
        check("has_more prefilter", status == 200 and page.get("messages") == [] and page.get("has_more") is True, f"status={status} {payload}")

        # Mention semua + deskripsi + alih kepemilikan grup.
        status, payload = request("POST", "/conversations/send", {"id": group_id, "type": "text", "body": "@semua kumpul"}, token=nusa)
        mentions = payload.get("data", {}).get("message", {}).get("mentions", []) if payload.get("ok") else []
        check("mention all", status == 201 and 2 in mentions and len(mentions) >= 2, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/update_group", {"id": group_id, "description": "Grup smoke test"}, token=nusa)
        check("group description", status == 200 and payload["data"]["conversation"]["description"] == "Grup smoke test", f"status={status} {payload}")
        status, payload = request("POST", "/conversations/update_group", {"id": group_id, "description": "x" * 301}, token=nusa)
        check("description too long", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/update_group_settings", {"id": group_id, "disappearing_days": 7}, token=nusa)
        check("disappearing set", status == 200, f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/show?id={group_id}", token=nusa)
        check("disappearing shown", status == 200 and payload["data"]["conversation"]["disappearing_days"] == 7, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/update_group_settings", {"id": group_id, "disappearing_days": 5}, token=nusa)
        check("disappearing invalid", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/update_group_settings", {"id": group_id, "disappearing_days": 0}, token=nusa)
        check("disappearing off", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/transfer_owner", {"id": group_id, "user_id": 1}, token=sari)
        check("transfer by non-owner", status == 403, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/transfer_owner", {"id": group_id, "user_id": 999999}, token=nusa)
        check("transfer to non-member", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/transfer_owner", {"id": group_id, "user_id": 2}, token=nusa)
        check("transfer ownership", status == 200 and payload["data"]["owner_id"] == 2, f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/show?id={group_id}", token=sari)
        check("new owner shown", status == 200 and payload["data"]["conversation"]["created_by"] == 2, f"status={status} {payload}")
        status, payload = request("POST", "/conversations/transfer_owner", {"id": group_id, "user_id": 1}, token=sari)
        check("transfer back", status == 200 and payload["data"]["owner_id"] == 1, f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/messages?id={group_id}&limit=100", token=nusa)
        sys_msgs = [m for m in payload.get("data", {}).get("messages", []) if m.get("type") == "system"] if payload.get("ok") else []
        parsed = []
        for m in sys_msgs:
            try:
                parsed.append(json.loads(m.get("body") or ""))
            except Exception:
                parsed.append(None)
        codes = {p.get("sys") for p in parsed if isinstance(p, dict)}
        check("system structured", status == 200 and "owner_transfer" in codes and "info_update" in codes, f"codes={codes}")
        ot = next((p for p in parsed if isinstance(p, dict) and p.get("sys") == "owner_transfer"), {})
        check("system targets+names", ot.get("by") in (1, 2) and all(t in (1, 2) for t in ot.get("targets", [])) and str(ot.get("by")) in (ot.get("names") or {}), f"event={ot}")
        status, payload = request("GET", "/conversations?limit=100", token=nusa)
        convs = payload.get("data", {}).get("conversations", []) if payload.get("ok") else []
        grp = next((c for c in convs if c["id"] == group_id), {})
        preview = ((grp.get("last_message") or {}).get("body") or "")
        check("preview renders system", status == 200 and '{"sys"' not in preview and "mengalihkan" in preview, f"preview={preview[:80]}")

        # Sapaan pertemanan + remark.
        status, payload = request("POST", "/auth/register", {"username": f"gsap{suffix}", "password": "secret123", "display_name": "Sapa"})
        greet_token = payload.get("data", {}).get("auth", {}).get("access_token")
        greet_id = payload.get("data", {}).get("user", {}).get("id")
        status, payload = request("POST", "/friends/send", {"user_id": 1, "message": "Halo, aku teman lama"}, token=greet_token)
        greet_req = payload.get("data", {}).get("request", {}).get("request_id")
        status, payload = request("GET", "/friends/requests", token=nusa)
        incoming = payload.get("data", {}).get("incoming", []) if payload.get("ok") else []
        check("greeting shown", any(r["request_id"] == greet_req and r["greeting"] == "Halo, aku teman lama" for r in incoming), f"status={status} {payload}")
        status, payload = request("POST", "/friends/respond", {"request_id": greet_req, "action": "accept"}, token=nusa)
        check("accept greeter", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/friends/remark", {"user_id": greet_id, "remark": "Kawan Lama"}, token=nusa)
        check("set remark", status == 200 and payload["data"]["remark"] == "Kawan Lama", f"status={status} {payload}")
        status, payload = request("GET", "/friends", token=nusa)
        check("remark listed", status == 200 and any(f["id"] == greet_id and f.get("remark") == "Kawan Lama" for f in payload["data"]["friends"]), f"status={status}")
        status, payload = request("POST", "/conversations/create_direct", {"user_id": greet_id}, token=nusa)
        check("direct for remark", status in (200, 201), f"status={status} {payload}")
        status, payload = request("GET", "/conversations?limit=100", token=nusa)
        convs = payload.get("data", {}).get("conversations", []) if payload.get("ok") else []
        mine = [c for c in convs if c.get("peer", {}).get("id") == greet_id]
        check("batch present remark", status == 200 and len(mine) == 1 and mine[0]["title"] == "Kawan Lama" and mine[0]["peer"].get("friend_status") == "accepted", f"status={status}")
        status, payload = request("POST", "/friends/remark", {"user_id": greet_id, "remark": "x" * 41}, token=nusa)
        check("remark too long", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/friends/remove", {"user_id": greet_id}, token=nusa)
        check("remove greeter", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/friends/remark", {"user_id": greet_id, "remark": "x"}, token=nusa)
        check("remark non-friend", status == 403, f"status={status} {payload}")

        # Momen privat + edit + daftar suka + hapus komentar.
        status, payload = request("POST", "/moments/create", {"body": f"Rahasia-{suffix}", "audience": "private"}, token=nusa)
        priv_id = payload.get("data", {}).get("moment", {}).get("id")
        check("private moment", status == 201 and payload["data"]["moment"]["audience"] == "private", f"status={status} {payload}")
        status, payload = request("GET", "/moments?limit=50", token=sari)
        check("private hidden from feed", status == 200 and all(m["id"] != priv_id for m in payload["data"]["moments"]), f"status={status}")
        status, payload = request("GET", f"/moments/show?id={priv_id}", token=sari)
        check("private show forbidden", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/moments/like", {"id": priv_id}, token=sari)
        check("private like forbidden", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/moments/edit", {"id": priv_id, "body": f"Rahasia diubah-{suffix}"}, token=nusa)
        check("edit moment", status == 200 and payload["data"]["moment"]["body"] == f"Rahasia diubah-{suffix}" and payload["data"]["moment"]["edited_at"], f"status={status} {payload}")
        status, payload = request("POST", "/moments/edit", {"id": priv_id, "body": "diubah orang"}, token=sari)
        check("edit others moment", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/moments/delete", {"id": priv_id}, token=nusa)
        check("delete private moment", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/moments/like", {"id": moment_id}, token=sari)
        check("like for list", status == 200, f"status={status} {payload}")
        status, payload = request("GET", f"/moments/likes?id={moment_id}", token=nusa)
        check("likers list", status == 200 and any(u["id"] == 2 for u in payload["data"]["users"]), f"status={status} {payload}")
        status, payload = request("POST", "/moments/comment", {"id": moment_id, "body": f"Komen hapus-{suffix}"}, token=sari)
        cdel = payload.get("data", {}).get("comment", {}).get("id")
        status, payload = request("POST", "/moments/comment_delete", {"id": cdel}, token=nusa)
        check("author deletes comment", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/moments/comment", {"id": moment_id, "body": f"Komen hapus 2-{suffix}"}, token=sari)
        cdel2 = payload.get("data", {}).get("comment", {}).get("id")
        status, payload = request("POST", "/moments/comment_delete", {"id": cdel2}, token=sari)
        check("own comment deleted", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/moments/comment_delete", {"id": 999999}, token=nusa)
        check("delete unknown comment", status == 404, f"status={status} {payload}")

        # Viewer story.
        status, payload = request("POST", "/moments/story_create", {"body": f"StoryView-{suffix}"}, token=nusa)
        sid = payload.get("data", {}).get("story", {}).get("id")
        status, payload = request("POST", "/moments/story_view", {"id": sid}, token=sari)
        check("story viewed", status == 200, f"status={status} {payload}")
        status, payload = request("GET", f"/moments/story_views?id={sid}", token=nusa)
        viewers = payload.get("data", {}).get("viewers", []) if payload.get("ok") else []
        check("story viewers", status == 200 and payload["data"]["view_count"] == 1 and any(v["id"] == 2 for v in viewers), f"status={status} {payload}")
        status, payload = request("GET", f"/moments/story_views?id={sid}", token=sari)
        check("viewers author only", status == 403, f"status={status} {payload}")
        status, payload = request("POST", "/moments/story_view", {"id": sid}, token=greet_token)
        check("stranger cannot view", status == 404, f"status={status} {payload}")
        status, payload = request("GET", "/moments/stories", token=nusa)
        check("stories carry counts", status == 200 and any(s["id"] == sid and s["view_count"] == 1 for s in payload["data"]["stories"]), f"status={status}")
        status, payload = request("POST", "/moments/story_delete", {"id": sid}, token=nusa)
        check("story cleanup", status == 200, f"status={status} {payload}")

        # Riwayat panggilan + tak terjawab.
        status, payload = request("GET", "/calls/history", token=nusa)
        hist = payload.get("data", {}).get("calls", []) if payload.get("ok") else []
        answered = [c for c in hist if c["id"] == call_id]
        check("history answered", status == 200 and answered and answered[0]["direction"] == "outgoing" and answered[0]["answered_at"] and answered[0]["missed"] is False, f"status={status} {payload}")
        status, payload = request("POST", "/calls/offer", {"to_user_id": 2, "sdp": fake_sdp}, token=nusa)
        miss_id = payload.get("data", {}).get("call", {}).get("id")
        status, payload = request("POST", "/calls/end", {"call_id": miss_id}, token=nusa)
        check("caller cancels", status == 200, f"status={status} {payload}")
        status, payload = request("GET", "/calls/history", token=sari)
        shist = payload.get("data", {}).get("calls", []) if payload.get("ok") else []
        missed = [c for c in shist if c["id"] == miss_id]
        check("missed call flagged", status == 200 and missed and missed[0]["missed"] is True and missed[0]["direction"] == "incoming", f"status={status} {payload}")

        # Laporan konten.
        status, payload = request("POST", "/reports/create", {"target_type": "user", "target_id": 2, "reason": "spam"}, token=nusa)
        check("report user", status == 201 and payload["data"]["report"]["status"] == "pending", f"status={status} {payload}")
        status, payload = request("POST", "/reports/create", {"target_type": "planet", "target_id": 2, "reason": "spam"}, token=nusa)
        check("report bad target", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/reports/create", {"target_type": "user", "target_id": 2, "reason": "benci"}, token=nusa)
        check("report bad reason", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/reports/create", {"target_type": "user", "target_id": 999999, "reason": "spam"}, token=nusa)
        check("report unknown", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/reports/create", {"target_type": "user", "target_id": 1, "reason": "spam"}, token=nusa)
        check("report self rejected", status == 404, f"status={status} {payload}")
        status, payload = request("GET", "/reports/mine", token=nusa)
        check("my reports", status == 200 and any(r["target_id"] == 2 for r in payload["data"]["reports"]), f"status={status} {payload}")

        # PIN dompet.
        status, payload = request("POST", "/auth/register", {"username": f"pin{suffix}", "password": "secret123", "display_name": "Pin"})
        pin_token = payload.get("data", {}).get("auth", {}).get("access_token")
        status, payload = request("POST", "/wallet/topup", {"amount": 10000}, token=pin_token)
        check("pinuser topup", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/set_pin", {"pin": "12"}, token=pin_token)
        check("pin too short", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/set_pin", {"pin": "1234"}, token=pin_token)
        check("pin set", status == 200 and payload["data"]["has_pin"] is True, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/transfer", {"to_user_id": 2, "amount": 100}, token=pin_token)
        check("transfer needs pin", status == 403 and payload["error"]["code"] == "pin_required", f"status={status} {payload}")
        status, payload = request("POST", "/wallet/transfer", {"to_user_id": 2, "amount": 100, "pin": "0000"}, token=pin_token)
        check("wrong pin rejected", status == 403, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/transfer", {"to_user_id": 2, "amount": 100, "pin": "1234"}, token=pin_token)
        check("transfer with pin", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/set_pin", {"pin": "5678"}, token=pin_token)
        check("change pin needs old", status == 401, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/set_pin", {"pin": "5678", "old_pin": "1234"}, token=pin_token)
        check("pin changed", status == 200, f"status={status} {payload}")

        # Tagih koin.
        status, payload = request("POST", "/auth/register", {"username": f"rqa{suffix}", "password": "secret123", "display_name": "ReqA"})
        rqa_token = payload.get("data", {}).get("auth", {}).get("access_token")
        status, payload = request("POST", "/auth/register", {"username": f"rqb{suffix}", "password": "secret123", "display_name": "ReqB"})
        rqb_token = payload.get("data", {}).get("auth", {}).get("access_token")
        rqb_id = payload.get("data", {}).get("user", {}).get("id")
        status, payload = request("POST", "/wallet/topup", {"amount": 5000}, token=rqb_token)
        check("reqb topup", status == 201, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/request_money", {"to_user_id": rqb_id, "amount": 1000, "note": "Ganti"}, token=rqa_token)
        req_id = payload.get("data", {}).get("request", {}).get("id")
        check("request money", status == 201 and req_id, f"status={status} {payload}")
        status, payload = request("GET", "/wallet/requests", token=rqb_token)
        check("incoming request", status == 200 and any(r["id"] == req_id for r in payload["data"]["incoming"]), f"status={status} {payload}")
        status, payload = request("POST", "/wallet/request_pay", {"request_id": req_id}, token=rqa_token)
        check("pay own request rejected", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/request_pay", {"request_id": req_id}, token=rqb_token)
        check("request paid", status == 200 and payload["data"]["paid"] is True, f"status={status} {payload}")
        status, payload = request("GET", "/wallet", token=rqa_token)
        check("requester credited", status == 200 and payload["data"]["balance"] == 1000, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/request_pay", {"request_id": req_id}, token=rqb_token)
        check("double pay rejected", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/request_money", {"to_user_id": rqb_id, "amount": 500}, token=rqa_token)
        req2 = payload.get("data", {}).get("request", {}).get("id")
        status, payload = request("POST", "/wallet/request_decline", {"request_id": req2}, token=rqb_token)
        check("request declined", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/request_money", {"to_user_id": rqb_id, "amount": 500}, token=rqa_token)
        req3 = payload.get("data", {}).get("request", {}).get("id")
        status, payload = request("POST", "/wallet/request_cancel", {"request_id": req3}, token=rqa_token)
        check("request cancelled", status == 200, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/request_pay", {"request_id": req3}, token=rqb_token)
        check("pay cancelled rejected", status == 422, f"status={status} {payload}")

        # Amplop merah.
        status, payload = request("POST", "/wallet/topup", {"amount": 100000}, token=nusa)
        check("redpacket funds", status == 201, f"status={status} {payload}")
        status, payload = request("GET", "/wallet", token=nusa)
        bal_before = payload["data"]["balance"] if payload.get("ok") else 0
        status, payload = request("POST", "/wallet/redpacket_create", {"conversation_id": group_id, "total": 100, "count": 2, "note": "Bagi rezeki"}, token=nusa)
        pkt = payload.get("data", {}).get("packet", {}) if payload.get("ok") else {}
        pkt_id = pkt.get("id")
        msg_ok = payload.get("data", {}).get("message", {}).get("type") == "redpacket" if payload.get("ok") else False
        check("redpacket created", status == 201 and pkt_id and msg_ok, f"status={status} {payload}")
        status, payload = request("GET", "/wallet", token=nusa)
        check("sender debited", status == 200 and payload["data"]["balance"] == bal_before - 100, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/redpacket_claim", {"packet_id": pkt_id}, token=sari)
        amt1 = payload.get("data", {}).get("amount", 0) if payload.get("ok") else 0
        check("first claim", status == 200 and payload["data"]["status"] == "claimed" and 1 <= amt1 <= 99, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/redpacket_claim", {"packet_id": pkt_id}, token=sari)
        check("claim once only", status == 200 and payload["data"]["status"] == "already" and payload["data"]["amount"] == amt1, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/redpacket_claim", {"packet_id": pkt_id}, token=nusa)
        check("sender cannot claim", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/redpacket_claim", {"packet_id": pkt_id}, token=dewi)
        check("second claim", status == 200 and payload["data"]["status"] == "claimed" and payload["data"]["packet"]["status"] == "finished", f"status={status} {payload}")
        status, payload = request("GET", f"/wallet/redpacket?id={pkt_id}", token=nusa)
        pkt_full = payload.get("data", {}).get("packet", {}) if payload.get("ok") else {}
        check("packet total exact", status == 200 and pkt_full.get("claimed_amount") == 100 and len(pkt_full.get("claims", [])) == 2, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/redpacket_claim", {"packet_id": pkt_id}, token=new_token)
        check("non-member claim hidden", status == 404, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/redpacket_create", {"conversation_id": group_id, "total": 5, "count": 10}, token=nusa)
        check("count over members", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/redpacket_create", {"conversation_id": group_id, "total": 1, "count": 2}, token=nusa)
        check("total below count", status == 422, f"status={status} {payload}")
        status, payload = request("POST", "/wallet/redpacket_create", {"conversation_id": group_id, "total": 9000000, "count": 1}, token=nusa)
        check("redpacket overdraft", status == 422, f"status={status} {payload}")

        # Gaya story.
        status, payload = request("POST", "/moments/story_create", {"body": f"Gaya-{suffix}", "style": "sunset"}, token=nusa)
        styled = payload.get("data", {}).get("story", {}).get("id") if payload.get("ok") else None
        check("story style", status == 201 and payload["data"]["story"]["style"] == "sunset", f"status={status} {payload}")
        status, payload = request("POST", "/moments/story_create", {"body": "x", "style": "neon"}, token=nusa)
        check("story style invalid", status == 422, f"status={status} {payload}")
        if styled:
            status, payload = request("POST", "/moments/story_delete", {"id": styled}, token=nusa)
            check("styled story cleanup", status == 200, f"status={status} {payload}")

        # Suka komentar.
        status, payload = request("POST", "/moments/comment", {"id": moment_id, "body": f"Suka komen-{suffix}"}, token=sari)
        like_cid = payload.get("data", {}).get("comment", {}).get("id")
        status, payload = request("POST", "/moments/like_comment", {"id": like_cid}, token=nusa)
        check("like comment", status == 200 and payload["data"]["like_count"] == 1, f"status={status} {payload}")
        status, payload = request("GET", f"/moments/comments?id={moment_id}", token=nusa)
        cmts = payload.get("data", {}).get("comments", []) if payload.get("ok") else []
        mine_c = [c for c in cmts if c["id"] == like_cid]
        check("comment like shown", status == 200 and mine_c and mine_c[0]["liked_by_me"] is True and mine_c[0]["like_count"] == 1, f"status={status} {payload}")
        status, payload = request("POST", "/moments/unlike_comment", {"id": like_cid}, token=nusa)
        check("unlike comment", status == 200 and payload["data"]["like_count"] == 0, f"status={status} {payload}")
        status, payload = request("POST", "/moments/comment_delete", {"id": like_cid}, token=nusa)
        check("liked comment cleanup", status == 200, f"status={status} {payload}")

        # Info dibaca per pesan.
        status, payload = request("GET", f"/conversations/message_reads?id={new_conv}&message_id={probe_id}", token=nusa)
        readers = payload.get("data", {}).get("read_by", []) if payload.get("ok") else []
        check("message reads", status == 200 and any(r["id"] == 1 for r in readers), f"status={status} {payload}")
        status, payload = request("GET", f"/conversations/message_reads?id={new_conv}&message_id=999999", token=nusa)
        # Relay: pesan tersapu tetap 200 (tanda baca dari anggota, bukan baris pesan).
        check("reads unknown message", status == 200 and payload["data"]["read_by"] == [], f"status={status} {payload}")

        # Tagar.
        tag = f"smoke{suffix}"
        status, payload = request("POST", "/moments/create", {"body": f"Halo #{tag} semua"}, token=nusa)
        tag_mid = payload.get("data", {}).get("moment", {}).get("id") if payload.get("ok") else None
        status, payload = request("GET", f"/moments/tag?tag={tag}", token=sari)
        tagged = payload.get("data", {}).get("moments", []) if payload.get("ok") else []
        check("tag feed", status == 200 and any(m["id"] == tag_mid for m in tagged), f"status={status} {payload}")
        status, payload = request("GET", "/moments/tag?tag=x", token=sari)
        check("tag too short", status == 422, f"status={status} {payload}")
        if tag_mid:
            status, payload = request("POST", "/moments/delete", {"id": tag_mid}, token=nusa)
            check("tagged moment cleanup", status == 200, f"status={status} {payload}")

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
