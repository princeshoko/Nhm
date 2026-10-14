#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="/home/purplepe/nerdhalla.net"
NAV="$ROOT/discord-nav.js"
LOGOUT="$ROOT/discord-logout.php"
STAMP="$(date +%Y%m%d_%H%M%S)"
MARKER="NERDHALLA FORCE LOGOUT NAV V1"

cd "$ROOT"

echo "===== BACKUP ====="
cp -a "$NAV" "$NAV.before_logout_click_fix_$STAMP"
cp -a "$LOGOUT" "$LOGOUT.before_logout_click_fix_$STAMP"
cp -a account.html "account.html.before_logout_click_fix_$STAMP"

echo
echo "===== ENSURE DRIVE LOGOUT CHAIN ====="
php <<'PHP'
<?php
$file = 'discord-logout.php';
$s = file_get_contents($file);
if ($s === false) {
    fwrite(STDERR, "Could not read discord-logout.php\n");
    exit(1);
}

$marker = 'NERDHALLA DRIVE LOGOUT CHAIN';
if (strpos($s, $marker) === false) {
    $needle = 'session_destroy();';
    $count = substr_count($s, $needle);
    if ($count !== 1) {
        fwrite(STDERR, "Expected exactly one session_destroy(); but found $count.\n");
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
} else {
    echo "Drive logout chain already present.\n";
}
?>
PHP
php -l "$LOGOUT"

echo
echo "===== FORCE REAL LOGOUT NAVIGATION ====="
if ! grep -Fq "$MARKER" "$NAV"; then
cat >> "$NAV" <<'JS'

/* ===== NERDHALLA FORCE LOGOUT NAV V1 ===== */
(() => {
  'use strict';

  const LOGOUT_URL = '/discord-logout.php';

  function logoutControl() {
    return document.getElementById('discord-login-nav');
  }

  function looksLikeLogout(el) {
    if (!el) return false;
    const text = String(el.textContent || '').toLowerCase();
    const href = String(el.getAttribute('href') || '').toLowerCase();
    return text.includes('logout') || href.includes('discord-logout.php');
  }

  function normalizeLogoutLink() {
    const el = logoutControl();
    if (!looksLikeLogout(el)) return;
    el.setAttribute('href', LOGOUT_URL);
  }

  document.addEventListener('click', (event) => {
    const el = event.target.closest && event.target.closest('#discord-login-nav');
    if (!looksLikeLogout(el)) return;

    event.preventDefault();
    event.stopPropagation();
    if (typeof event.stopImmediatePropagation === 'function') {
      event.stopImmediatePropagation();
    }

    window.location.assign(LOGOUT_URL);
  }, true);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', normalizeLogoutLink, {once:true});
  } else {
    normalizeLogoutLink();
  }

  const observer = new MutationObserver(normalizeLogoutLink);
  observer.observe(document.documentElement, {
    childList:true,
    subtree:true,
    characterData:true,
    attributes:true,
    attributeFilter:['href','class']
  });
})();
/* ===== END NERDHALLA FORCE LOGOUT NAV V1 ===== */
JS
    echo "Forced logout navigation added."
else
    echo "Forced logout navigation already present."
fi

echo
echo "===== BUMP NAV SCRIPT CACHE ====="
for f in *.html; do
    [ -f "$f" ] || continue
    if grep -q 'discord-nav\.js' "$f"; then
        cp -a "$f" "$f.before_logout_nav_cache_$STAMP"
        php -r '
            $f=$argv[1];
            $s=file_get_contents($f);
            $s=preg_replace(
                "~discord-nav\\.js(?:\\?v=[^\"'\''[:space:]<>]+)?~",
                "discord-nav.js?v=20261014-drive-logout-1",
                $s
            );
            file_put_contents($f,$s);
        ' "$f"
    fi
done

echo
echo "===== VERIFY ====="
grep -n -A5 -B2 "$MARKER" "$NAV" | head -30
grep -n "NERDHALLA DRIVE LOGOUT CHAIN" "$LOGOUT"
grep -n "discord-nav.js?v=20261014-drive-logout-1" account.html | head -5
curl -sS -o /dev/null -w 'Account HTTP: %{http_code}\n' https://nerdhalla.net/account.html

echo
echo "===== DONE ====="
echo "Hard-refresh once, then Logout should perform a real page navigation."
