<?php
declare(strict_types=1);
/**
 * Create the database schema.
 *
 *   php tools/migrate.php           # create missing tables (safe to re-run)
 *   php tools/migrate.php --fresh   # delete local SQLite DB first
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

$driver = strtolower((string) env_value($env, 'DB_DRIVER', 'sqlite'));
$fresh = in_array('--fresh', $argv, true);

if ($driver === 'mysql') {
    $host = (string) env_value($env, 'DB_HOST', '127.0.0.1');
    $port = (string) env_value($env, 'DB_PORT', '3306');
    $name = (string) env_value($env, 'DB_NAME', 'nusachat');
    $dsn = "mysql:host={$host};port={$port};dbname={$name};charset=utf8mb4";
    $pdo = new PDO($dsn, (string) env_value($env, 'DB_USER', 'root'), (string) env_value($env, 'DB_PASS', ''), [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
    ]);
    $schema = $root . '/database/schema.mysql.sql';
    echo "Migrating MySQL database {$name}...\n";
} else {
    $path = (string) env_value($env, 'DB_PATH', 'storage/nusachat.sqlite');
    if (!str_starts_with($path, '/')) {
        $path = $root . '/' . $path;
    }
    if ($fresh && is_file($path)) {
        unlink($path);
        foreach (['-wal', '-shm'] as $suffix) {
            if (is_file($path . $suffix)) {
                unlink($path . $suffix);
            }
        }
        echo "Deleted existing SQLite database.\n";
    }
    if (!is_dir(dirname($path))) {
        mkdir(dirname($path), 0775, true);
    }
    $pdo = new PDO('sqlite:' . $path, null, null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
    ]);
    $pdo->exec('PRAGMA foreign_keys = ON');
    $pdo->exec('PRAGMA journal_mode = WAL');
    $schema = $root . '/database/schema.sqlite.sql';
    echo "Migrating SQLite database: {$path}\n";
}

$sql = file_get_contents($schema);
if ($sql === false) {
    fwrite(STDERR, "Cannot read schema: {$schema}\n");
    exit(1);
}
$sql = preg_replace('/^\s*--.*$/m', '', $sql) ?? $sql;
$statements = array_filter(array_map('trim', preg_split('/;\s*\n/', $sql)));
$count = 0;
$run = static function (string $statement) use ($pdo, &$count): void {
    try {
        $pdo->exec($statement);
    } catch (PDOException $e) {
        // ALTER TABLE ... ADD COLUMN gagal bila kolom sudah ada (database lama
        // yang dimigrasi lalu migrate dijalankan ulang). Itu aman diabaikan.
        // Sama untuk CREATE INDEX yang sudah ada (MySQL tak punya IF NOT EXISTS).
        if (preg_match('/duplicate (column|key name)/i', $e->getMessage())) {
            return;
        }
        throw $e;
    }
    $count++;
};
// Urutan tiga fase: CREATE dulu (DB kosong butuh tabel sebelum ALTER),
// lalu ALTER (kolom baru untuk DB lama), terakhir sisanya (indeks dkk.
// yang boleh merujuk kolom baru). Aman di-run ulang: CREATE/INDEX
// memakai IF NOT EXISTS, ALTER yang duplikat diabaikan di $run.
$creates = [];
$alters = [];
$rest = [];
foreach ($statements as $statement) {
    if ($statement === '' || strpos($statement, '--') === 0) {
        continue;
    }
    if (preg_match('/^\s*CREATE\s+TABLE/i', $statement)) {
        $creates[] = $statement;
    } elseif (preg_match('/^\s*ALTER\s+TABLE/i', $statement)) {
        $alters[] = $statement;
    } else {
        $rest[] = $statement;
    }
}
foreach (array_merge($creates, $alters, $rest) as $statement) {
    $run($statement);
}
echo "OK: {$count} statements executed using " . basename($schema) . ".\n";
