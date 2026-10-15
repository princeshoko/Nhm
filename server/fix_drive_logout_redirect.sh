#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="/home/purplepe/nerdhalla.net"
FILE="$ROOT/discord-logout.php"
STAMP="$(date +%Y%m%d_%H%M%S)"
MARKER="NERDHALLA DRIVE LOGOUT RETURN V1"

cd "$ROOT"

echo "===== BACKUP ====="
cp -a "$FILE" "$FILE.before_drive_logout_return_$STAMP"

echo
echo "===== PATCH DRIVE LOGOUT RETURN ====="
php <<'PHP'
<?php
$file = 'discord-logout.php';
$s = file_get_contents($file);

if ($s === false) {
    fwrite(STDERR, "Could not read discord-logout.php\n");
    exit(1);
}

$marker = 'NERDHALLA DRIVE LOGOUT RETURN V1';

if (strpos($s, $marker) !== false) {
    echo "Drive logout return already installed.\n";
    exit(0);
}

$needle = 'session_destroy();';
$count = substr_count($s, $needle);

if ($count !== 1) {
    fwrite(STDERR, "Expected exactly one session_destroy(); but found $count. No changes made.\n");
    exit(1);
}

$replacement = <<<'CODE'
session_destroy();

/* ===== NERDHALLA DRIVE LOGOUT RETURN V1 ===== */
if (
    isset($_POST['drive']) &&
    hash_equals('done', (string)$_POST['drive'])
) {
    header('Location: /', true, 303);
    exit;
}
/* ===== END NERDHALLA DRIVE LOGOUT RETURN V1 ===== */
CODE;

$s = str_replace($needle, $replacement, $s);

if (file_put_contents($file, $s) === false) {
    fwrite(STDERR, "Could not write discord-logout.php\n");
    exit(1);
}

echo "Drive logout redirect installed.\n";
?>
PHP

echo
echo "===== VERIFY PHP ====="
php -l "$FILE"

echo
echo "===== VERIFY PATCH ====="
grep -n -A10 -B2 "$MARKER" "$FILE"

echo
echo "===== DONE ====="
echo "Drive-initiated POST logout now redirects to Nerdhalla home with HTTP 303."
