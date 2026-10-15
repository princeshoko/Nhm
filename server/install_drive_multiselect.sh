#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="/var/www/nerdhalla-drive/private"
RAW="https://raw.githubusercontent.com/princeshoko/Nhm/main/server"
STAMP="$(date +%Y%m%d_%H%M%S)"
TMP="$(mktemp -d /tmp/nerdhalla-drive-multiselect.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: run this on Venom as root."
  exit 1
fi

mkdir -p "$ROOT"

echo "===== DOWNLOAD UPDATED DRIVE UI ====="
curl -fsSL "$RAW/drive-private.html" -o "$TMP/index.html"
curl -fsSL "$RAW/drive-private.css"  -o "$TMP/app.css"
curl -fsSL "$RAW/drive-private.js"   -o "$TMP/app.js"

echo
echo "===== VERIFY DOWNLOADS ====="
grep -Fq 'id="delete-selected-button"' "$TMP/index.html"
grep -Fq 'id="select-all"' "$TMP/index.html"
grep -Fq "updateSelectionControls" "$TMP/app.js"
grep -Fq "Delete selected" "$TMP/app.js"
grep -Fq "row-select" "$TMP/app.js"
grep -Fq "grid-template-columns:34px" "$TMP/app.css"
echo "Multi-select UI markers: OK"

if command -v node >/dev/null 2>&1; then
  node --check "$TMP/app.js"
  echo "JavaScript syntax: OK"
else
  echo "JavaScript syntax check: skipped (node not installed)"
fi

echo
echo "===== BACKUP CURRENT DRIVE UI ====="
for f in index.html app.css app.js; do
  if [ -f "$ROOT/$f" ]; then
    cp -a "$ROOT/$f" "$ROOT/$f.before_multiselect_$STAMP"
    echo "$ROOT/$f.before_multiselect_$STAMP"
  fi
done

echo
echo "===== INSTALL ====="
install -m 0644 "$TMP/index.html" "$ROOT/index.html"
install -m 0644 "$TMP/app.css" "$ROOT/app.css"
install -m 0644 "$TMP/app.js" "$ROOT/app.js"

echo
echo "===== VERIFY LIVE FILES ====="
grep -n 'delete-selected-button\|select-all' "$ROOT/index.html"
grep -n 'updateSelectionControls\|Delete selected\|row-select' "$ROOT/app.js" | head -30

echo
echo "===== HTTP ====="
curl -sS -o /dev/null -w 'Drive app.js HTTP: %{http_code}\n'   'https://drive.nerdhalla.net/private/app.js?v=20261015-multiselect1'
curl -sS -o /dev/null -w 'Drive app.css HTTP: %{http_code}\n'   'https://drive.nerdhalla.net/private/app.css?v=20261015-multiselect1'

echo
echo "===== DONE ====="
echo "No service restart was required; these are static Drive UI files."
