#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="/home/purplepe/nerdhalla.net"
FILE="$ROOT/discord-logout.php"
STAMP="$(date +%Y%m%d_%H%M%S)"

if [ ! -f "$FILE" ]; then
    echo "ERROR: $FILE not found."
    exit 1
fi

cd "$ROOT"

echo "===== BACKUP ====="
cp -a "$FILE" "$FILE.before_drive_logout_$STAMP"

echo
echo "===== PATCH LOGOUT ====="
php <<'PHP'
<?php
$file = 'discord-logout.php';
$s = file_get_contents($file);
if ($s === false) {
    fwrite(STDERR, "Could not read discord-logout.php\n");
    exit(1);
}

$marker = 'NERDHALLA DRIVE LOGOUT CHAIN';
if (strpos($s, $marker) !== false) {
    echo "Drive logout chain already installed.\n";
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

/* NERDHALLA DRIVE LOGOUT CHAIN */
header('Location: https://drive.nerdhalla.net/private/logout', true, 302);
exit;
/* END NERDHALLA DRIVE LOGOUT CHAIN */
CODE;

$s = str_replace($needle, $replacement, $s);
if (file_put_contents($file, $s) === false) {
    fwrite(STDERR, "Could not write discord-logout.php\n");
    exit(1);
}

echo "Drive logout chain installed.\n";
?>
PHP

echo
echo "===== VERIFY PHP ====="
php -l "$FILE"

echo
echo "===== VERIFY PATCH ====="
grep -n -A7 -B3 "NERDHALLA DRIVE LOGOUT CHAIN" "$FILE"

echo
echo "===== DONE ====="
echo "Logging out of Nerdhalla now also revokes the private Drive session."
