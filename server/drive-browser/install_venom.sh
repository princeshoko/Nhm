#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

STAMP="$(date +%Y%m%d_%H%M%S)"
BASE="https://raw.githubusercontent.com/princeshoko/Nhm/main/server/drive-browser"
ROOT="/var/www/nerdhalla-drive"
SERVICE_PY="/usr/local/lib/nerdhalla-drive-web.py"
UNIT="/etc/systemd/system/nerdhalla-drive-web.service"
ENVFILE="/etc/nerdhalla-drive-web.env"
VHOST="/etc/httpd/conf.d/drive.nerdhalla.net-le-ssl.conf"

backup_if_exists() {
  local f="$1"
  if [ -e "$f" ]; then
    cp -a "$f" "$f.before_browser_upload_$STAMP"
    echo "Backed up: $f"
  fi
}

echo "===== NERDHALLA DRIVE BROWSER MANAGER ====="

echo
echo "===== PRECHECK ====="
[ -f /mnt/storage/nerdhalla-drive/accounts.json ] || { echo "ERROR: Drive registry missing."; exit 1; }
[ -f "$VHOST" ] || { echo "ERROR: Drive SSL vhost not found: $VHOST"; exit 1; }
/usr/bin/python3 -c 'import aiohttp' >/dev/null 2>&1 || { echo "ERROR: aiohttp is not available to /usr/bin/python3"; exit 1; }
httpd -M 2>/dev/null | grep -q 'proxy_module' || { echo "ERROR: Apache proxy_module is not loaded."; exit 1; }
httpd -M 2>/dev/null | grep -q 'proxy_http_module' || { echo "ERROR: Apache proxy_http_module is not loaded."; exit 1; }

PID="$(systemctl show -p MainPID --value sharla.service)"
[[ "$PID" =~ ^[0-9]+$ ]] && [ "$PID" -gt 1 ] || { echo "ERROR: Sharla is not running."; exit 1; }
SECRET="$(tr '\0' '\n' < "/proc/$PID/environ" | sed -n 's/^NERDHALLA_PORTAL_SECRET=//p' | head -1)"
[ -n "$SECRET" ] || { echo "ERROR: Could not read NERDHALLA_PORTAL_SECRET from the running Sharla service."; exit 1; }
SECRET_B64="$(printf '%s' "$SECRET" | base64 -w0)"
unset SECRET

backup_if_exists "$SERVICE_PY"
backup_if_exists "$UNIT"
backup_if_exists "$ENVFILE"
backup_if_exists "$VHOST"
backup_if_exists "$ROOT/manage/index.html"

install -d -m 0755 /usr/local/lib "$ROOT/manage"

TMP_SERVICE="$(mktemp)"
TMP_HTML="$(mktemp)"
trap 'rm -f "$TMP_SERVICE" "$TMP_HTML"' EXIT

curl -fsSL "$BASE/drive_web_service.py" -o "$TMP_SERVICE"
curl -fsSL "$BASE/manage.html" -o "$TMP_HTML"

/usr/bin/python3 -m py_compile "$TMP_SERVICE"
install -m 0755 "$TMP_SERVICE" "$SERVICE_PY"
install -m 0644 "$TMP_HTML" "$ROOT/manage/index.html"

printf 'NERDHALLA_DRIVE_WEB_SECRET_B64=%s\n' "$SECRET_B64" > "$ENVFILE"
chmod 0600 "$ENVFILE"
unset SECRET_B64

cat > "$UNIT" <<'UNITFILE'
[Unit]
Description=Nerdhalla Drive Browser File Manager API
After=network-online.target local-fs.target
Wants=network-online.target

[Service]
Type=simple
User=root
Group=root
EnvironmentFile=/etc/nerdhalla-drive-web.env
ExecStart=/usr/bin/python3 /usr/local/lib/nerdhalla-drive-web.py
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNITFILE

python3 - "$VHOST" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
start = '# BEGIN NERDHALLA DRIVE BROWSER API'
end = '# END NERDHALLA DRIVE BROWSER API'
block = '''
    # BEGIN NERDHALLA DRIVE BROWSER API
    ProxyTimeout 3600
    ProxyPass /api/ http://127.0.0.1:8766/ retry=0 timeout=3600
    ProxyPassReverse /api/ http://127.0.0.1:8766/
    <Location "/api/">
        LimitRequestBody 0
    </Location>
    # END NERDHALLA DRIVE BROWSER API
'''
if start in s:
    before = s.split(start, 1)[0]
    rest = s.split(start, 1)[1]
    if end not in rest:
        raise SystemExit('ERROR: Existing browser API marker is incomplete')
    after = rest.split(end, 1)[1]
    s = before + block + after
else:
    pos = s.rfind('</VirtualHost>')
    if pos < 0:
        raise SystemExit('ERROR: No closing </VirtualHost> found in Drive SSL vhost')
    s = s[:pos] + block + s[pos:]
p.write_text(s)
PY

systemctl daemon-reload
systemctl enable --now nerdhalla-drive-web.service
sleep 1

curl -fsS http://127.0.0.1:8766/health >/dev/null
httpd -t
systemctl reload httpd
sleep 1

echo
echo "===== VERIFY ====="
printf 'Service: '
systemctl is-active nerdhalla-drive-web.service
printf 'Local API: '
curl -fsS http://127.0.0.1:8766/health
echo
printf 'Public API: '
curl -fsS https://drive.nerdhalla.net/api/health
echo
curl -fsSI https://drive.nerdhalla.net/manage/ | head -8

echo
echo "===== DONE ====="
echo "Venom browser manager backend is installed."