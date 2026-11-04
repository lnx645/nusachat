<?php
declare(strict_types=1);
/**
 * Seed demo data for the mobile app.
 *
 *   php tools/seed.php
 *
 * Accounts (password: password123):
 *   nusa, sari, budi, dewi
 */
$root = dirname(__DIR__);
$envFile = $root . '/.env';
$env = is_file($envFile) ? (parse_ini_file($envFile, false, INI_SCANNER_RAW) ?: []) : [];
function e(array $env, string $key, mixed $default = null): mixed
{
    $value = getenv($key);
    if ($value !== false && $value !== '') {
        return $value;
    }
    return $env[$key] ?? $default;
}
function now_iso_seed(): string
{
    return gmdate('Y-m-d\TH:i:s\Z');
}
function uuid_seed(): string
{
    $b = random_bytes(16);
    $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
    $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($b), 4));
}

$driver = strtolower((string) e($env, 'DB_DRIVER', 'sqlite'));
if ($driver === 'mysql') {
    $dsn = 'mysql:host=' . e($env, 'DB_HOST', '127.0.0.1') . ';port=' . e($env, 'DB_PORT', '3306')
        . ';dbname=' . e($env, 'DB_NAME', 'nusachat') . ';charset=utf8mb4';
    $pdo = new PDO($dsn, (string) e($env, 'DB_USER', 'root'), (string) e($env, 'DB_PASS', ''));
} else {
    $path = (string) e($env, 'DB_PATH', 'storage/nusachat.sqlite');
    if (!str_starts_with($path, '/')) {
        $path = $root . '/' . $path;
    }
    $pdo = new PDO('sqlite:' . $path);
    $pdo->exec('PRAGMA journal_mode = WAL');
}
$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
$pdo->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);

$secret = (string) e($env, 'APP_SECRET', 'dev-secret-change-me');
$hashPhone = static fn (string $phone): string => hash_hmac('sha256', 'phone:' . $phone, $secret);

$users = [
    ['nusa', 'Nusa Pratama', 'male', -6.2000, 106.8160, 'https://api.dicebear.com/9.x/avataaars/png?seed=Nusa'],
    ['sari', 'Sari Wulandari', 'female', -6.2050, 106.8200, 'https://api.dicebear.com/9.x/avataaars/png?seed=Sari'],
    ['budi', 'Budi Santoso', 'male', -6.1950, 106.8100, 'https://api.dicebear.com/9.x/avataaars/png?seed=Budi'],
    ['dewi', 'Dewi Lestari', 'female', -6.2100, 106.8250, 'https://api.dicebear.com/9.x/avataaars/png?seed=Dewi'],
];

$passwordHash = password_hash('password123', PASSWORD_DEFAULT);
$ids = [];
$now = now_iso_seed();

foreach ($users as $index => [$username, $displayName, $gender, $lat, $lng, $avatar]) {
    $existing = $pdo->prepare('SELECT * FROM users WHERE username = ?');
    $existing->execute([$username]);
    $row = $existing->fetch();
    if (!$row) {
        $phone = '62812345678' . ($index + 1);
        $stmt = $pdo->prepare(
            'INSERT INTO users (uuid, username, phone, phone_hash, password_hash, display_name, avatar_url, bio, gender,
                                latitude, longitude, location_updated_at, discoverable, last_seen_at, token_version,
                                is_active, created_at, updated_at)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1,?,0,1,?,?)'
        );
        $stmt->execute([
            uuid_seed(), $username, $phone, $hashPhone($phone), $passwordHash, $displayName, $avatar,
            'Halo, saya ' . $displayName . '.', $gender, $lat, $lng, $now, $now, $now, $now,
        ]);
        $ids[$username] = (int) $pdo->lastInsertId();
    } else {
        $ids[$username] = (int) $row['id'];
    }
}

echo "Users: " . implode(', ', array_map(
    static fn (string $username, int $id): string => "{$username}={$id}",
    array_keys($ids),
    array_values($ids)
)) . "\n";

$makeFriends = static function (string $a, string $b) use ($pdo, $ids, $now): void {
    $from = $ids[$a];
    $to = $ids[$b];
    $stmt = $pdo->prepare('SELECT id FROM friendships WHERE (requester_id=? AND addressee_id=?) OR (requester_id=? AND addressee_id=?)');
    $stmt->execute([$from, $to, $to, $from]);
    if ($stmt->fetch()) {
        return;
    }
    $insert = $pdo->prepare('INSERT INTO friendships (requester_id, addressee_id, status, created_at, updated_at) VALUES (?,?,?,?,?)');
    $insert->execute([$from, $to, 'accepted', $now, $now]);
};
$makeFriends('nusa', 'sari');
$makeFriends('nusa', 'budi');
$makeFriends('sari', 'dewi');

// One pending request: budi -> dewi.
$stmt = $pdo->prepare('SELECT id FROM friendships WHERE requester_id=? AND addressee_id=?');
$stmt->execute([$ids['budi'], $ids['dewi']]);
if (!$stmt->fetch()) {
    $insert = $pdo->prepare('INSERT INTO friendships (requester_id, addressee_id, status, created_at, updated_at) VALUES (?,?,?,?,?)');
    $insert->execute([$ids['budi'], $ids['dewi'], 'pending', $now, $now]);
}

$makeConversation = static function (string $a, string $b, array $messages) use ($pdo, $ids, $now): void {
    $aId = $ids[$a];
    $bId = $ids[$b];
    $key = min($aId, $bId) . ':' . max($aId, $bId);
    $stmt = $pdo->prepare('SELECT * FROM conversations WHERE direct_key = ?');
    $stmt->execute([$key]);
    $conversation = $stmt->fetch();
    if (!$conversation) {
        $insert = $pdo->prepare('INSERT INTO conversations (uuid, type, created_by, direct_key, created_at, updated_at) VALUES (?,?,?,?,?,?)');
        $insert->execute([uuid_seed(), 'direct', $aId, $key, $now, $now]);
        $conversationId = (int) $pdo->lastInsertId();
        $member = $pdo->prepare('INSERT INTO conversation_members (conversation_id, user_id, role, joined_at, last_read_message_id, muted) VALUES (?,?,?,?,0,0)');
        $member->execute([$conversationId, $aId, 'member', $now]);
        $member->execute([$conversationId, $bId, 'member', $now]);
    } else {
        $conversationId = (int) $conversation['id'];
    }

    $count = (int) $pdo->query('SELECT COUNT(*) FROM messages WHERE conversation_id = ' . $conversationId)->fetchColumn();
    if ($count > 0) {
        return;
    }

    $offset = 60;
    foreach ($messages as $message) {
        [$sender, $body] = $message;
        $created = gmdate('Y-m-d\TH:i:s\Z', time() - $offset);
        $expires = gmdate('Y-m-d\TH:i:s\Z', time() - $offset + 30 * 86400);
        $insert = $pdo->prepare('INSERT INTO messages (uuid, conversation_id, sender_id, type, body, status, client_id, created_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?)');
        $insert->execute([uuid_seed(), $conversationId, $ids[$sender], 'text', $body, 'read', 'seed-' . uuid_seed(), $created, $expires]);
        $messageId = (int) $pdo->lastInsertId();
        // Relay: penerima snapshot; seed dianggap sudah dibaca semua pihak.
        $recipient = $pdo->prepare('INSERT INTO message_recipients (message_id, user_id, acked_at) VALUES (?,?,?)');
        $recipient->execute([$messageId, $aId, $created]);
        $recipient->execute([$messageId, $bId, $created]);
        $pdo->prepare('UPDATE conversations SET last_message_id=?, last_message_at=?, updated_at=? WHERE id=?')
            ->execute([$messageId, $created, $created, $conversationId]);
        $offset -= 7;
    }
    $pdo->prepare('UPDATE conversation_members SET last_read_message_id = (SELECT MAX(id) FROM messages WHERE conversation_id = ?) WHERE conversation_id = ?')
        ->execute([$conversationId, $conversationId]);
};

$makeConversation('nusa', 'sari', [
    ['nusa', 'Halo Sari, apa kabar?'],
    ['sari', 'Baik! Kamu sudah coba fitur baru di NusaChat?'],
    ['nusa', 'Sudah, momen dan nearby-nya jalan.'],
    ['sari', 'Mantap. Nanti kita ngopi ya.'],
]);
$makeConversation('nusa', 'budi', [
    ['budi', 'Bro, grup futsal jadi minggu ini?'],
    ['nusa', 'Jadi. Jam 7 malam ya.'],
]);

$momentCount = (int) $pdo->query('SELECT COUNT(*) FROM moments')->fetchColumn();
if ($momentCount === 0) {
    $insertMoment = $pdo->prepare('INSERT INTO moments (uuid, user_id, body, media_json, created_at) VALUES (?,?,?,?,?)');
    $insertMoment->execute([uuid_seed(), $ids['sari'], 'Senja di Jakarta selalu punya cerita. 🌇', null, gmdate('Y-m-d\TH:i:s\Z', time() - 3600)]);
    $insertMoment->execute([uuid_seed(), $ids['budi'], 'Baru selesai futsal, lanjut makan bakso!', null, gmdate('Y-m-d\TH:i:s\Z', time() - 7200)]);
    $insertMoment->execute([uuid_seed(), $ids['nusa'], 'Selamat pagi! Jangan lupa sarapan sebelum ngoding.', null, gmdate('Y-m-d\TH:i:s\Z', time() - 10800)]);
    $momentId = (int) $pdo->lastInsertId();
    $pdo->prepare('INSERT INTO moment_likes (moment_id, user_id, created_at) VALUES (?,?,?)')
        ->execute([$momentId, $ids['sari'], now_iso_seed()]);
    echo "Moments seeded.\n";
}

echo "Seed complete. Login with nusa / password123 (also sari, budi, dewi).\n";
