# TURN Server — Cloudflare (nusachat)

> **Token tetap di server.** API Token tidak di-commit ke frontend / repo public.

## App

- **Name:** `nusachat`
- **Turn Token ID:** `c6c1abdf36230f2bc354c4566d74fcc6`
- **API Token:** `0c7d1d011c3be6a061e67f80efd0deae5eb6788dc26db62f74d682a2e41b5fe5` — simpan di server (ENV `TURN_API_TOKEN`), jangan expose di aplikasi.

## Generate ICE Servers (server-side)

Back-end memanggil Cloudflare untuk membuat kredensial berumur pendek (`ttl` detik), lalu meneruskan `iceServers` ke client untuk `RTCPeerConnection`.

```bash
curl \
  -H "Authorization: Bearer 0c7d1d011c3be6a061e67f80efd0deae5eb6788dc26db62f74d682a2e41b5fe5" \
  -H "Content-Type: application/json" \
  -d '{"ttl": 86400}' \
  https://rtc.live.cloudflare.com/v1/turn/keys/c6c1abdf36230f2bc354c4566d74fcc6/credentials/generate-ice-servers
```

### Contoh response

```json
{
  "iceServers": [
    {
      "urls": [
        "stun:stun.cloudflare.com:3478",
        "turn:turn.cloudflare.com:3478?transport=udp",
        "turn:turn.cloudflare.com:3478?transport=tcp",
        "turns:turn.cloudflare.com:5349?transport=tcp"
      ],
      "username": "xxxx",
      "credential": "yyyy"
    }
  ]
}
```

Gunakan `iceServers` tersebut saat membuat `RTCPeerConnection` di frontend. Kredensial bersifat short-lived — generate per sesi/panggilan di server, jangan hardcode di client.
