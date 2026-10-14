#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

STAMP="$(date +%Y%m%d_%H%M%S)"
SHARLA="/root/sharla"
PORTAL="$SHARLA/sharla_web_portal.py"
MODULE="$SHARLA/nerdhalla_drive_browser.py"
WEBBIN="/usr/local/sbin/nerdhalla-drive-web.py"
SERVICE="/etc/systemd/system/nerdhalla-drive-web.service"
PRIVATE_WEB="/var/www/nerdhalla-drive/private"
RAW="https://raw.githubusercontent.com/princeshoko/Nhm/main/server"
MARKER="# ===== NERDHALLA DRIVE PRIVATE BROWSER HOOK ====="

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: run this on Venom as root."
    exit 1
fi

cd "$SHARLA"

echo "===== BACKUP ====="
cp -a "$PORTAL" "$PORTAL.before_private_drive_$STAMP"
[ ! -f "$MODULE" ] || cp -a "$MODULE" "$MODULE.before_private_drive_$STAMP"
[ ! -f "$WEBBIN" ] || cp -a "$WEBBIN" "$WEBBIN.before_private_drive_$STAMP"
[ ! -f "$SERVICE" ] || cp -a "$SERVICE" "$SERVICE.before_private_drive_$STAMP"
for f in /etc/httpd/conf.d/drive.nerdhalla.net.conf /etc/httpd/conf.d/drive.nerdhalla.net-le-ssl.conf; do
    [ -f "$f" ] && cp -a "$f" "$f.before_private_drive_$STAMP"
done

echo
echo "===== DOWNLOAD PRIVATE DRIVE SERVICES ====="
curl -fsSL "$RAW/nerdhalla_drive_browser.py" -o "$MODULE"
curl -fsSL "$RAW/nerdhalla-drive-web.py" -o "$WEBBIN"
mkdir -p "$PRIVATE_WEB"
curl -fsSL "$RAW/drive-private.html" -o "$PRIVATE_WEB/index.html"
curl -fsSL "$RAW/drive-private.css" -o "$PRIVATE_WEB/app.css"
curl -fsSL "$RAW/drive-private.js" -o "$PRIVATE_WEB/app.js"
chmod 600 "$MODULE"
chmod 700 "$WEBBIN"
chmod 755 "$PRIVATE_WEB"
chmod 644 "$PRIVATE_WEB/index.html" "$PRIVATE_WEB/app.css" "$PRIVATE_WEB/app.js"
python3 -m py_compile "$MODULE" "$WEBBIN" "$PORTAL"
python3 -c 'import aiohttp; print("aiohttp OK:", aiohttp.__version__)'

if ! grep -Fq "$MARKER" "$PORTAL"; then
cat >> "$PORTAL" <<'PYHOOK'

# ===== NERDHALLA DRIVE PRIVATE BROWSER HOOK =====
_original_install_sharla_web_portal_drive_private_browser = install_sharla_web_portal

def install_sharla_web_portal(bot, app):
    _original_install_sharla_web_portal_drive_private_browser(bot, app)
    import nerdhalla_drive_browser
    nerdhalla_drive_browser.install_drive_browser(
        bot,
        app,
        _authenticate,
    )
# ===== END NERDHALLA DRIVE PRIVATE BROWSER HOOK =====
PYHOOK
fi
python3 -m py_compile "$PORTAL"

cat > "$SERVICE" <<'UNIT'
[Unit]
Description=Nerdhalla Private Drive Browser API
After=network-online.target local-fs.target
Wants=network-online.target

[Service]
Type=simple
User=root
Group=root
ExecStart=/usr/bin/python3 /usr/local/sbin/nerdhalla-drive-web.py
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ReadWritePaths=/home /mnt/storage/nerdhalla-drive

[Install]
WantedBy=multi-user.target
UNIT
chmod 644 "$SERVICE"

echo
echo "===== MAKE RAW DRIVE URLS PRIVATE ====="
python3 - <<'PYAPACHE'
from pathlib import Path

paths = [
    Path('/etc/httpd/conf.d/drive.nerdhalla.net.conf'),
    Path('/etc/httpd/conf.d/drive.nerdhalla.net-le-ssl.conf'),
]
marker = '# NERDHALLA DRIVE PRIVATE BROWSER V1'
block = r'''

    # NERDHALLA DRIVE PRIVATE BROWSER V1
    ProxyPreserveHost On
    ProxyTimeout 3600
    ProxyPass /private/ http://127.0.0.1:5091/private/
    ProxyPassReverse /private/ http://127.0.0.1:5091/private/
    ProxyPass /private http://127.0.0.1:5091/private
    ProxyPassReverse /private http://127.0.0.1:5091/private
    ProxyPass /api/ http://127.0.0.1:5091/api/
    ProxyPassReverse /api/ http://127.0.0.1:5091/api/
    RedirectMatch 302 ^/u(?:/.*)?$ https://nerdhalla.net/account.html#drive
    # END NERDHALLA DRIVE PRIVATE BROWSER V1
'''

found = 0
for path in paths:
    if not path.exists():
        continue
    found += 1
    text = path.read_text()
    lines = []
    for line in text.splitlines(True):
        if 'AliasMatch' in line and '^/u/' in line and not line.lstrip().startswith('#'):
            lines.append('# NERDHALLA PRIVATE DISABLED: ' + line)
        else:
            lines.append(line)
    text = ''.join(lines)
    if marker in text and 'ProxyPass /private/' not in text:
        text = text.replace(
            '    ProxyPreserveHost On\n',
            '    ProxyPreserveHost On\n'
            '    ProxyPass /private/ http://127.0.0.1:5091/private/\n'
            '    ProxyPassReverse /private/ http://127.0.0.1:5091/private/\n'
            '    ProxyPass /private http://127.0.0.1:5091/private\n'
            '    ProxyPassReverse /private http://127.0.0.1:5091/private\n',
            1,
        )
    if marker not in text:
        idx = text.rfind('</VirtualHost>')
        if idx < 0:
            raise SystemExit(f'ERROR: no </VirtualHost> in {path}')
        text = text[:idx] + block + '\n' + text[idx:]
    path.write_text(text)

if found == 0:
    raise SystemExit('ERROR: Drive Apache vhost files were not found.')
PYAPACHE

echo
echo "===== START / RELOAD ====="
systemctl daemon-reload
systemctl enable nerdhalla-drive-web.service >/dev/null 2>&1 || true
systemctl restart nerdhalla-drive-web.service
systemctl restart sharla.service
httpd -t
systemctl reload httpd

echo
echo "===== VERIFY ====="
echo "Drive browser API: $(systemctl is-active nerdhalla-drive-web.service)"
echo "Sharla:            $(systemctl is-active sharla.service)"
echo "Apache:            $(systemctl is-active httpd)"
curl -sS -o /dev/null -w 'Drive root HTTP: %{http_code}\n' https://drive.nerdhalla.net/
curl -sS -o /dev/null -w 'Private API without session HTTP: %{http_code}\n' https://drive.nerdhalla.net/api/list
curl -sS -o /dev/null -w 'Private window without session HTTP: %{http_code}\n' https://drive.nerdhalla.net/private/
curl -sS -I https://drive.nerdhalla.net/u/private-test/ | sed -n '1,8p'
ss -ltnp | grep ':5091' || true

echo
echo "===== DONE ====="
echo "Expected: API without a session = 401; /u/... = 302 to account.html#drive"
