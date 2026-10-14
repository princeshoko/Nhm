#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="/home/purplepe/nerdhalla.net"
STAMP="$(date +%Y%m%d_%H%M%S)"
ACCOUNT="$ROOT/account.html"
PORTAL="$ROOT/portal-api.php"
RAW="https://raw.githubusercontent.com/princeshoko/Nhm/main/server"

if [ ! -d "$ROOT" ]; then
    echo "ERROR: $ROOT not found. Run this on the cPanel/Hydra shell."
    exit 1
fi

cd "$ROOT"

echo "===== BACKUP ====="
cp -a "$ACCOUNT" "$ACCOUNT.before_private_drive_browser_$STAMP"
cp -a "$PORTAL" "$PORTAL.before_private_drive_browser_$STAMP"

echo
echo "===== ADD DRIVE BROWSER TOKEN ROUTE ====="
php <<'PHP'
<?php
$file = 'portal-api.php';
$s = file_get_contents($file);
if ($s === false) {
    fwrite(STDERR, "Could not read portal-api.php\n");
    exit(1);
}
$route = "    'drive_browser_token' => array('method'=>'GET','path'=>'/portal/me/drive/browser-token','admin'=>false),\n";
if (strpos($s, "'drive_browser_token'") === false) {
    $patterns = array(
        "/^(\\s*'drive'\\s*=>\\s*array\\([^\\n]+\\),\\s*)$/m",
        "/^(\\s*'email_eligibility'\\s*=>\\s*array\\([^\\n]+\\),\\s*)$/m",
        "/^(\\s*'challenges'\\s*=>\\s*array\\([^\\n]+\\),\\s*)$/m"
    );
    $done = false;
    foreach ($patterns as $pattern) {
        $next = preg_replace($pattern, "$1\n" . rtrim($route, "\n"), $s, 1, $count);
        if ($next !== null && $count === 1) {
            $s = $next;
            $done = true;
            break;
        }
    }
    if (!$done) {
        fwrite(STDERR, "Could not find a safe portal route insertion point.\n");
        exit(1);
    }
    file_put_contents($file, $s);
    echo "Added drive_browser_token route.\n";
} else {
    echo "drive_browser_token route already present.\n";
}
?>
PHP
php -l "$PORTAL"

echo
echo "===== DOWNLOAD PRIVATE DRIVE UI ====="
CSS_FILE="$(mktemp)"
JS_FILE="$(mktemp)"
PATCHER="$(mktemp)"
trap 'rm -f "$CSS_FILE" "$JS_FILE" "$PATCHER"' EXIT
curl -fsSL "$RAW/drive-browser-ui.css" -o "$CSS_FILE"
curl -fsSL "$RAW/drive-browser-ui.js" -o "$JS_FILE"

cat > "$PATCHER" <<'PHP'
<?php
$file = $argv[1];
$cssFile = $argv[2];
$jsFile = $argv[3];
$s = file_get_contents($file);
$css = file_get_contents($cssFile);
$js = file_get_contents($jsFile);
if ($s === false || $css === false || $js === false) {
    fwrite(STDERR, "Could not read account/CSS/JS source.\n");
    exit(1);
}
if (strpos($s, 'NERDHALLA PRIVATE DRIVE BROWSER V1') === false) {
    $pos = strripos($s, '</head>');
    if ($pos === false) {
        fwrite(STDERR, "account.html has no </head>.\n");
        exit(1);
    }
    $s = substr($s, 0, $pos) . "\n<style>\n" . $css . "\n</style>\n" . substr($s, $pos);
}
if (strpos($s, 'Private browser files') === false) {
    $pos = strripos($s, '</body>');
    if ($pos === false) {
        fwrite(STDERR, "account.html has no </body>.\n");
        exit(1);
    }
    $s = substr($s, 0, $pos) . "\n<script>\n" . $js . "\n</script>\n" . substr($s, $pos);
}
file_put_contents($file, $s);
echo "Private Drive browser manager installed.\n";
?>
PHP
php "$PATCHER" "$ACCOUNT" "$CSS_FILE" "$JS_FILE"

echo
echo "===== VERIFY ====="
grep -n "drive_browser_token" "$PORTAL"
grep -n "NERDHALLA PRIVATE DRIVE BROWSER V1" "$ACCOUNT" | head -5
curl -sS -o /dev/null -w 'account.html HTTP: %{http_code}\n' https://nerdhalla.net/account.html
curl -sS -o /dev/null -w 'Private API without browser session HTTP: %{http_code}\n' https://drive.nerdhalla.net/api/list
curl -sS -I https://drive.nerdhalla.net/u/private-test/ | sed -n '1,8p'

echo
echo "===== DONE ====="
echo "Open https://nerdhalla.net/account.html#drive while logged in with Discord."
