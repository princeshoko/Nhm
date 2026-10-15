#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="/home/purplepe/nerdhalla.net"
ASSET_URL="https://raw.githubusercontent.com/princeshoko/Nhm/main/branding/nerdhalla-logo.webp"
ASSET="$ROOT/nerdhalla-logo.webp"
STAMP="$(date +%Y%m%d_%H%M%S)"
LIST="$(mktemp)"
TMP="$(mktemp)"
trap 'rm -f "$LIST" "$TMP"' EXIT

if [ ! -d "$ROOT" ]; then
    echo "ERROR: $ROOT not found. Run this on the cPanel/Hydra shell."
    exit 1
fi

cd "$ROOT"

echo "===== DOWNLOAD NEW NERDHALLA LOGO ====="
curl -fsSL "$ASSET_URL" -o "$TMP"
test -s "$TMP"
file "$TMP"

echo
echo "===== FIND WEBSITE LOGO REFERENCES ====="
find . -maxdepth 2 -type f \
    \( -name '*.html' -o -name '*.shtml' -o -name '*.css' \) \
    -not -name '*.backup_*' -not -name '*.before_*' \
    -print0 |
while IFS= read -r -d '' file; do
    if grep -qE 'bot-icon\.php\?server=854996860224262144|nerdhalla-logo\.webp' "$file"; then
        printf '%s\n' "$file" >> "$LIST"
    fi
done

sort -u "$LIST" -o "$LIST"

if [ -s "$LIST" ]; then
    cat "$LIST"
else
    echo "No existing server-logo references found; the asset will still be installed."
fi

echo
echo "===== BACKUP ====="
if [ -f "$ASSET" ]; then
    cp -p "$ASSET" "$ASSET.before_brand_$STAMP"
fi

if [ -s "$LIST" ]; then
    while IFS= read -r file; do
        cp -p "$file" "$file.before_brand_$STAMP"
    done < "$LIST"
fi

echo
echo "===== INSTALL LOGO ====="
cp -f "$TMP" "$ASSET"
chmod 644 "$ASSET"

echo
echo "===== UPDATE WEBSITE BRAND REFERENCES ====="
if [ -s "$LIST" ]; then
    export NH_BRAND_LIST="$LIST"
    php <<'PHP'
<?php
$root = '/home/purplepe/nerdhalla.net';
$list = getenv('NH_BRAND_LIST');
$files = file($list, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
if ($files === false) {
    fwrite(STDERR, "Could not read file list.\n");
    exit(1);
}

$pattern = '~(?:https://(?:www\.)?nerdhalla\.net/)?/?bot-icon\.php\?server=854996860224262144(?:&amp;|&)[^"\'<>\s)]*~i';

foreach ($files as $rel) {
    $path = $root . '/' . ltrim($rel, './');
    $s = file_get_contents($path);
    if ($s === false) {
        fwrite(STDERR, "Could not read $path\n");
        exit(1);
    }

    $next = preg_replace($pattern, '/nerdhalla-logo.webp', $s);
    if ($next === null) {
        fwrite(STDERR, "Regex failed for $path\n");
        exit(1);
    }

    if ($next !== $s) {
        file_put_contents($path, $next);
        echo "Updated: $rel\n";
    }
}
?>
PHP
fi

echo
echo "===== VERIFY ====="
ls -lh "$ASSET"

echo
echo "Remaining old Nerdhalla server-logo references:"
grep -RniE \
  --include='*.html' --include='*.shtml' --include='*.css' \
  'bot-icon\.php\?server=854996860224262144' . \
  | grep -vE '\.before_|\.backup_' \
  | head -40 || true

echo
echo "New logo references:"
grep -Rni \
  --include='*.html' --include='*.shtml' --include='*.css' \
  'nerdhalla-logo.webp' . \
  | grep -vE '\.before_|\.backup_' \
  | head -40 || true

echo
echo "===== LIVE CHECK ====="
curl -sS -o /dev/null -w 'Logo HTTP: %{http_code}\n' https://nerdhalla.net/nerdhalla-logo.webp
curl -sS -o /dev/null -w 'Homepage HTTP: %{http_code}\n' https://nerdhalla.net/

echo
echo "===== DONE ====="
