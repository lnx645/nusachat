<?php
declare(strict_types=1);
/**
 * One-shot Fase 1: kosongkan history pesan permanen pra-relay.
 *
 *   php tools/purge_history.php --yes
 *
 * Skema Fase 1 bersifat aditif, jadi pengosongan data lama dilakukan sekali
 * lewat tool ini (bukan di berkas skema yang di-run ulang tiap migrate).
 * Aman di-run ulang: DELETE tanpa baris cocok = no-op.
 */
$root = dirname(__DIR__);
$envFile = $root . '/.env';
$env = [];
if (is_file($envFile)) {
    $env = parse_ini_file($envFile, false, INI_SCANNER_RAW) ?: [];
}
function env_value(array $env, string $key, mixed $default = null): mixed
{
    $value = getenv($key);
    if ($value !== false && $value !== '') {
        return $value;
    }
    return $env[$key] ?? $default;
}

if (!in_array('--yes', $argv, true)) {
    fwrite(STDERR, "Refusing without --yes: this deletes all message history.\n");
    exit(2);
}

$driver = strtolower((string) env_value($env, 'DB_DRIVER', 'sqlite'));
if ($driver === 'mysql') {
    $host = (string) env_value($env, 'DB_HOST', '127.0.0.1');
    $port = (string) env_value($env, 'DB_PORT', '3306');
    $name = (string) env_value($env, 'DB_NAME', 'nusachat');
    $pdo = new PDO(
        "mysql:host={$host};port={$port};dbname={$name};charset=utf8mb4",
        (string) env_value($env, 'DB_USER', 'root'),
        (string) env_value($env, 'DB_PASS', ''),
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );
} else {
    $path = (string) env_value($env, 'DB_PATH', 'storage/nusachat.sqlite');
    if (!str_starts_with($path, '/')) {
        $path = $root . '/' . $path;
    }
    $pdo = new PDO('sqlite:' . $path, null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
}

$tables = [
    'message_reactions',
    'conversation_pins',
    'starred_messages',
    'hidden_messages',
    'message_recipients',
    'messages',
];
foreach ($tables as $table) {
    try {
        $deleted = $pdo->exec("DELETE FROM {$table}");
        echo "purged {$table}: {$deleted} rows\n";
    } catch (PDOException $e) {
        // Tabel belum ada (migrate Fase 1 belum jalan): lewati.
        echo "skip {$table}: {$e->getMessage()}\n";
    }
}
echo "OK: message history purged.\n";
