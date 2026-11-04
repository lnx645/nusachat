#!/usr/bin/env php
<?php
declare(strict_types=1);
/**
 * NusaChat realtime gateway (WebSocket, RFC 6455).
 *
 *   php tools/ws-server.php
 *   WS_BIND=tcp://0.0.0.0:8090 php tools/ws-server.php
 *
 * Clients connect with ?token=<access token>. The API writes events to the
 * realtime_events table; this process tails that table and pushes each event
 * to the sockets of the target user. No external dependency is required.
 */
$root = dirname(__DIR__);
$envFile = $root . '/.env';
$env = is_file($envFile) ? (parse_ini_file($envFile, false, INI_SCANNER_RAW) ?: []) : [];
function ws_env(array $env, string $key, mixed $default = null): mixed
{
    $value = getenv($key);
    if ($value !== false && $value !== '') {
        return $value;
    }
    return $env[$key] ?? $default;
}

/**
 * Retry transient SQLite lock errors instead of letting the gateway exit.
 * The API and this daemon share one database, so a short write burst must not
 * take realtime delivery down.
 */
function ws_db_retry(callable $fn, string $label, int $attempts = 4): mixed
{
    for ($attempt = 1; $attempt <= $attempts; $attempt++) {
        try {
            return $fn();
        } catch (PDOException $e) {
            $message = strtolower($e->getMessage());
            $transient = str_contains($message, 'locked') || str_contains($message, 'busy');
            if (!$transient || $attempt === $attempts) {
                fwrite(STDERR, "[ws] {$label} failed: {$e->getMessage()}\n");
                return null;
            }
            usleep(150000 * $attempt);
        }
    }
    return null;
}

$driver = strtolower((string) ws_env($env, 'DB_DRIVER', 'sqlite'));
if ($driver === 'mysql') {
    $dsn = 'mysql:host=' . ws_env($env, 'DB_HOST', '127.0.0.1') . ';port=' . ws_env($env, 'DB_PORT', '3306')
        . ';dbname=' . ws_env($env, 'DB_NAME', 'nusachat') . ';charset=utf8mb4';
    $pdo = new PDO($dsn, (string) ws_env($env, 'DB_USER', 'root'), (string) ws_env($env, 'DB_PASS', ''), [
        PDO::ATTR_TIMEOUT => 10,
    ]);
} else {
    $path = (string) ws_env($env, 'DB_PATH', 'storage/nusachat.sqlite');
    if (!str_starts_with($path, '/')) {
        $path = $root . '/' . $path;
    }
    $pdo = new PDO('sqlite:' . $path, null, null, [
        PDO::ATTR_TIMEOUT => 10,
    ]);
    $pdo->exec('PRAGMA journal_mode = WAL');
    $pdo->exec('PRAGMA busy_timeout = 10000');
}
$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
$pdo->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);

$secret = (string) ws_env($env, 'APP_SECRET', 'dev-secret-change-me');
$envName = strtolower((string) ws_env($env, 'APP_ENV', 'development'));
if ($envName === 'production' && (strlen($secret) < 32 || str_starts_with($secret, 'dev-secret'))) {
    fwrite(STDERR, "Refusing to start: APP_SECRET is still the dev default. Set a random 32+ byte secret.\n");
    exit(1);
}
$bind = (string) ws_env($env, 'WS_BIND', 'tcp://0.0.0.0:8090');
$server = @stream_socket_server($bind, $errno, $errstr);
if (!$server) {
    fwrite(STDERR, "Cannot bind {$bind}: {$errstr} ({$errno})\n");
    exit(1);
}
stream_set_blocking($server, false);
echo "NusaChat WS gateway listening on {$bind}\n";

function b64url_decode_ws(string $raw): string
{
    $pad = strlen($raw) % 4;
    if ($pad) {
        $raw .= str_repeat('=', 4 - $pad);
    }
    return base64_decode(strtr($raw, '-_', '+/'), true) ?: '';
}

function ws_verify_token(string $token, string $secret): ?array
{
    $parts = explode('.', $token);
    if (count($parts) !== 3) {
        return null;
    }
    [$head, $body, $sig] = $parts;
    $expected = hash_hmac('sha256', $head . '.' . $body, $secret, true);
    if (!hash_equals($expected, b64url_decode_ws($sig))) {
        return null;
    }
    $payload = json_decode(b64url_decode_ws($body), true);
    if (!is_array($payload) || ($payload['typ'] ?? '') !== 'access') {
        return null;
    }
    if (!isset($payload['exp']) || time() >= (int) $payload['exp']) {
        return null;
    }
    return $payload;
}

function ws_frame(string $payload, int $opcode = 0x1): string
{
    $length = strlen($payload);
    $header = chr(0x80 | $opcode);
    if ($length < 126) {
        $header .= chr($length);
    } elseif ($length < 65536) {
        $header .= chr(126) . pack('n', $length);
    } else {
        $header .= chr(127) . pack('J', $length);
    }
    return $header . $payload;
}

function ws_close( $socket, int $code = 1000): void
{
    @fwrite($socket, ws_frame(pack('n', $code), 0x8));
    @fclose($socket);
}

function ws_parse_frames(string $buffer, array &$frames): int
{
    $offset = 0;
    $total = strlen($buffer);
    while ($total - $offset >= 2) {
        $first = ord($buffer[$offset]);
        $second = ord($buffer[$offset + 1]);
        $fin = ($first & 0x80) !== 0;
        $opcode = $first & 0x0f;
        $masked = ($second & 0x80) !== 0;
        $length = $second & 0x7f;
        $pos = $offset + 2;
        if ($length === 126) {
            if ($total - $pos < 2) {
                break;
            }
            $length = unpack('n', substr($buffer, $pos, 2))[1];
            $pos += 2;
        } elseif ($length === 127) {
            if ($total - $pos < 8) {
                break;
            }
            $length = unpack('J', substr($buffer, $pos, 8))[1];
            $pos += 8;
        }
        $mask = '';
        if ($masked) {
            if ($total - $pos < 4) {
                break;
            }
            $mask = substr($buffer, $pos, 4);
            $pos += 4;
        }
        if ($total - $pos < $length) {
            break;
        }
        $payload = substr($buffer, $pos, $length);
        if ($masked) {
            for ($i = 0; $i < $length; $i++) {
                $payload[$i] = chr(ord($payload[$i]) ^ ord($mask[$i % 4]));
            }
        }
        $frames[] = ['fin' => $fin, 'opcode' => $opcode, 'payload' => $payload];
        $offset = $pos + $length;
    }
    return $offset;
}

$clients = [];       // resource id => ['sock', 'user_id', 'buffer', 'fragment', 'connected_at', 'last_ping']
$nextClientId = 1;
$cursor = (int) (ws_db_retry(
    static fn (): mixed => $pdo->query('SELECT COALESCE(MAX(id), 0) FROM realtime_events')->fetchColumn(),
    'event cursor init'
) ?? 0);
$lastPrune = time();
$lastPoll = 0.0;
$pingAt = 0;

while (true) {
    $read = [$server];
    $write = null;
    $except = null;
    foreach ($clients as $client) {
        $read[] = $client['sock'];
    }
    $ready = @stream_select($read, $write, $except, 0, 400000);

    if ($ready === false) {
        usleep(100000);
        continue;
    }

    foreach ($read as $socket) {
        if ($socket === $server) {
            $conn = @stream_socket_accept($server, 0);
            if (!$conn) {
                continue;
            }
            stream_set_timeout($conn, 3);
            $request = '';
            while (strpos($request, "\r\n\r\n") === false && strlen($request) < 16384) {
                $chunk = fread($conn, 2048);
                if ($chunk === '' || $chunk === false) {
                    break;
                }
                $request .= $chunk;
            }
            if (!preg_match('#^GET\s+([^\s]+)\s+HTTP/1\.1#i', $request, $matches)) {
                ws_close($conn, 1002);
                continue;
            }
            $target = $matches[1];
            $query = [];
            $queryString = parse_url($target, PHP_URL_QUERY);
            if ($queryString) {
                parse_str($queryString, $query);
            }
            $token = (string) ($query['token'] ?? '');
            $claims = ws_verify_token($token, $secret);
            if (!$claims) {
                $body = "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
                @fwrite($conn, $body);
                @fclose($conn);
                continue;
            }
            $userId = (int) ($claims['sub'] ?? 0);
            $user = null;
            if ($userId > 0) {
                $user = ws_db_retry(static function () use ($pdo, $userId): mixed {
                    $stmt = $pdo->prepare('SELECT id, token_version FROM users WHERE id = ? AND is_active = 1');
                    $stmt->execute([$userId]);
                    return $stmt->fetch();
                }, 'auth lookup');
            }
            if ($user === null) {
                $body = "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
                @fwrite($conn, $body);
                @fclose($conn);
                continue;
            }
            if (!$user || (int) $user['token_version'] !== (int) ($claims['tv'] ?? 0)) {
                $body = "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
                @fwrite($conn, $body);
                @fclose($conn);
                continue;
            }
            if (!preg_match('/Sec-WebSocket-Key:\s*(\S+)/i', $request, $keyMatch)) {
                ws_close($conn, 1002);
                continue;
            }
            $accept = base64_encode(sha1(trim($keyMatch[1]) . '258EAFA5-E914-47DA-95CA-C5AB0DC85B11', true));
            $response = "HTTP/1.1 101 Switching Protocols\r\n"
                . "Upgrade: websocket\r\n"
                . "Connection: Upgrade\r\n"
                . "Sec-WebSocket-Accept: {$accept}\r\n\r\n";
            @fwrite($conn, $response);
            stream_set_blocking($conn, false);
            $id = $nextClientId++;
            $clients[$id] = [
                'sock' => $conn,
                'user_id' => $userId,
                'buffer' => '',
                'fragment' => '',
                'connected_at' => time(),
                'last_ping' => time(),
            ];
            ws_db_retry(static function () use ($pdo, $userId): mixed {
                return $pdo->prepare('UPDATE users SET last_seen_at = ? WHERE id = ?')
                    ->execute([gmdate('Y-m-d\TH:i:s\Z'), $userId]);
            }, 'presence update');
            @fwrite($conn, ws_frame(json_encode([
                'type' => 'connected',
                'data' => ['user_id' => $userId, 'server_time' => gmdate('Y-m-d\TH:i:s\Z')],
            ], JSON_UNESCAPED_SLASHES)));
            echo "Client #{$id} connected (user {$userId}), total " . count($clients) . "\n";
            continue;
        }

        $clientId = null;
        foreach ($clients as $id => $client) {
            if ($client['sock'] === $socket) {
                $clientId = $id;
                break;
            }
        }
        if ($clientId === null) {
            continue;
        }

        $data = @fread($socket, 65536);
        if ($data === '' || $data === false || feof($socket)) {
            @fclose($socket);
            unset($clients[$clientId]);
            echo "Client #{$clientId} disconnected, total " . count($clients) . "\n";
            continue;
        }

        $clients[$clientId]['buffer'] .= $data;
        $frames = [];
        $consumed = ws_parse_frames($clients[$clientId]['buffer'], $frames);
        $clients[$clientId]['buffer'] = substr($clients[$clientId]['buffer'], $consumed);

        foreach ($frames as $frame) {
            $opcode = $frame['opcode'];
            if ($opcode === 0x8) {
                ws_close($socket, 1000);
                unset($clients[$clientId]);
                continue 2;
            }
            if ($opcode === 0x9) {
                @fwrite($socket, ws_frame($frame['payload'], 0xA));
                continue;
            }
            if ($opcode === 0xA) {
                continue;
            }
            if ($opcode === 0x0 || $opcode === 0x1) {
                $clients[$clientId]['fragment'] .= $frame['payload'];
                if ($frame['fin']) {
                    $text = $clients[$clientId]['fragment'];
                    $clients[$clientId]['fragment'] = '';
                    $incoming = json_decode($text, true);
                    if (is_array($incoming) && ($incoming['type'] ?? '') === 'ping') {
                        @fwrite($socket, ws_frame(json_encode([
                            'type' => 'pong',
                            'data' => ['server_time' => gmdate('Y-m-d\TH:i:s\Z')],
                        ], JSON_UNESCAPED_SLASHES)));
                    }
                }
            }
        }
    }

    $now = microtime(true);
    if ($now - $lastPoll >= 0.35) {
        $lastPoll = $now;
        $events = ws_db_retry(static function () use ($pdo, $cursor): mixed {
            $stmt = $pdo->prepare('SELECT * FROM realtime_events WHERE id > ? ORDER BY id ASC LIMIT 200');
            $stmt->execute([$cursor]);
            return $stmt->fetchAll();
        }, 'event poll') ?? [];
        foreach ($events as $event) {
            $cursor = max($cursor, (int) $event['id']);
            $userId = (int) $event['user_id'];
            foreach ($clients as $client) {
                if ($client['user_id'] !== $userId) {
                    continue;
                }
                $payload = json_decode($event['payload'], true);
                $envelope = json_encode([
                    'event_id' => (int) $event['id'],
                    'type' => $payload['type'] ?? 'event',
                    'data' => $payload['data'] ?? null,
                    'ts' => $event['created_at'],
                ], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
                @fwrite($client['sock'], ws_frame($envelope));
            }
        }
    }

    $now = time();
    if ($now - $pingAt >= 25) {
        $pingAt = $now;
        foreach ($clients as $client) {
            @fwrite($client['sock'], ws_frame('', 0x9));
        }
    }
    if ($now - $lastPrune >= 300) {
        $lastPrune = $now;
        $cutoff = gmdate('Y-m-d\TH:i:s\Z', time() - 86400);
        ws_db_retry(static function () use ($pdo, $cutoff): mixed {
            $stmt = $pdo->prepare('DELETE FROM realtime_events WHERE created_at < ?');
            $stmt->execute([$cutoff]);
            return $stmt->rowCount();
        }, 'event prune');
    }
}
