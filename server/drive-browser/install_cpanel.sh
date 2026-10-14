#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

cd /home/purplepe/nerdhalla.net
STAMP="$(date +%Y%m%d_%H%M%S)"
BASE="https://raw.githubusercontent.com/princeshoko/Nhm/main/server/drive-browser"
PHPCLI="php"
command -v php >/dev/null 2>&1 || PHPCLI="/opt/cpanel/ea-php81/root/usr/bin/php"

[ -f discord-auth-common.php ] || { echo "ERROR: discord-auth-common.php is missing."; exit 1; }
[ -f account.html ] || { echo "ERROR: account.html is missing."; exit 1; }

if [ -f drive-manage.php ]; then
  cp -a drive-manage.php "drive-manage.php.before_browser_upload_$STAMP"
fi
cp -a account.html "account.html.before_browser_upload_$STAMP"

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
curl -fsSL "$BASE/drive-manage.php" -o "$TMP"
"$PHPCLI" -l "$TMP"
install -m 0644 "$TMP" drive-manage.php

"$PHPCLI" <<'PHP'
<?php
$file = 'account.html';
$s = file_get_contents($file);
if ($s === false) {
    fwrite(STDERR, "Could not read account.html\n");
    exit(1);
}
$start = '<!-- BEGIN NERDHALLA DRIVE BROWSER MANAGER LINK -->';
$end = '<!-- END NERDHALLA DRIVE BROWSER MANAGER LINK -->';
$block = <<<'HTML'
<!-- BEGIN NERDHALLA DRIVE BROWSER MANAGER LINK -->
<style>
.nh-drive-browser-manager-slot{margin:14px 0 4px;display:flex;gap:10px;flex-wrap:wrap}
.nh-drive-browser-manager-link{display:inline-flex;align-items:center;justify-content:center;min-height:46px;padding:0 16px;border-radius:13px;border:1px solid rgba(255,255,255,.14);background:linear-gradient(90deg,#7d51f5,#a673ff);color:#fff!important;text-decoration:none!important;font-weight:850;box-shadow:0 12px 30px rgba(155,108,255,.16)}
.nh-drive-browser-manager-link:hover{transform:translateY(-1px)}
</style>
<script>
document.addEventListener('DOMContentLoaded',function(){
  if(document.getElementById('nh-drive-browser-manager-link')) return;
  var web=document.getElementById('nh-drive-web');
  if(!web) return;
  var panel=web.closest('[data-sharla-panel],.mysharla-panel,section') || web.parentElement;
  if(!panel) return;
  var slot=document.createElement('div');
  slot.className='nh-drive-browser-manager-slot';
  var link=document.createElement('a');
  link.id='nh-drive-browser-manager-link';
  link.className='nh-drive-browser-manager-link';
  link.href='/drive-manage.php';
  link.textContent='📁 Manage / Upload Files';
  slot.appendChild(link);
  panel.appendChild(slot);
});
</script>
<!-- END NERDHALLA DRIVE BROWSER MANAGER LINK -->
HTML;
if (strpos($s, $start) !== false) {
    $pattern = '~' . preg_quote($start, '~') . '.*?' . preg_quote($end, '~') . '~s';
    $s = preg_replace($pattern, $block, $s, 1, $count);
    if ($count !== 1) {
        fwrite(STDERR, "Could not refresh existing Drive manager block.\n");
        exit(1);
    }
} else {
    $pos = strripos($s, '</body>');
    if ($pos === false) {
        fwrite(STDERR, "Could not find </body> in account.html\n");
        exit(1);
    }
    $s = substr($s, 0, $pos) . $block . "\n" . substr($s, $pos);
}
if (file_put_contents($file, $s) === false) {
    fwrite(STDERR, "Could not write account.html\n");
    exit(1);
}
echo "Account Drive manager link installed.\n";
PHP

echo
echo "===== VERIFY ====="
"$PHPCLI" -l drive-manage.php
grep -n 'nh-drive-browser-manager-link' account.html | head
curl -fsSI https://nerdhalla.net/drive-manage.php | head -10 || true
curl -fsS -o /dev/null -w 'Account HTTP %{http_code}\n' https://nerdhalla.net/account.html

echo
echo "===== DONE ====="
echo "Browser upload entry point installed on Nerdhalla."