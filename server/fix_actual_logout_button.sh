#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="/home/purplepe/nerdhalla.net"
NAV="$ROOT/discord-nav.js"
LOGOUT="$ROOT/discord-logout.php"
STAMP="$(date +%Y%m%d_%H%M%S)"

cd "$ROOT"

echo "===== BACKUP ====="
cp -a "$NAV" "$NAV.before_actual_logout_fix_$STAMP"
cp -a "$LOGOUT" "$LOGOUT.before_actual_logout_fix_$STAMP"

echo
echo "===== ENSURE DISCORD LOGOUT CHAINS THROUGH DRIVE ====="
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

    echo "Drive logout redirect installed.\n";
} else {
    echo "Drive logout redirect already present.\n";
}
?>
PHP

php -l "$LOGOUT"

echo
echo "===== FIX ACTUAL LOGOUT BUTTON ====="
php <<'PHP'
<?php
$file = 'discord-nav.js';
$s = file_get_contents($file);

if ($s === false) {
    fwrite(STDERR, "Could not read discord-nav.js\n");
    exit(1);
}

/* Remove the earlier fixes that incorrectly targeted #discord-login-nav. */
$patterns = array(
    '~\n?/\* ===== NERDHALLA FORCE LOGOUT NAV V1 ===== \*/.*?/\* ===== END NERDHALLA FORCE LOGOUT NAV V1 ===== \*/\s*~s',
    '~\n?/\* ===== NERDHALLA FORCE LOGOUT NAV V2 ===== \*/.*?/\* ===== END NERDHALLA FORCE LOGOUT NAV V2 ===== \*/\s*~s'
);

foreach ($patterns as $pattern) {
    $s = preg_replace($pattern, "\n", $s);
}

$marker = 'NERDHALLA ACTUAL LOGOUT NAV V3';

if (strpos($s, $marker) === false) {
    $s .= <<<'JS'

/* ===== NERDHALLA ACTUAL LOGOUT NAV V3 ===== */
(() => {
  'use strict';

  const LOGOUT_URL = '/discord-logout.php';

  function normalizeLogout() {
    const link = document.getElementById('discord-logout-nav');
    if (!link) return;
    link.setAttribute('href', LOGOUT_URL);
  }

  document.addEventListener('click', (event) => {
    const link = event.target.closest && event.target.closest('#discord-logout-nav');
    if (!link) return;

    event.preventDefault();
    event.stopPropagation();
    if (typeof event.stopImmediatePropagation === 'function') {
      event.stopImmediatePropagation();
    }

    window.location.assign(LOGOUT_URL);
  }, true);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', normalizeLogout, {once:true});
  } else {
    normalizeLogout();
  }

  const observer = new MutationObserver(normalizeLogout);
  observer.observe(document.documentElement, {
    childList:true,
    subtree:true,
    attributes:true,
    attributeFilter:['href']
  });
})();
/* ===== END NERDHALLA ACTUAL LOGOUT NAV V3 ===== */
JS;
}

if (file_put_contents($file, $s) === false) {
    fwrite(STDERR, "Could not write discord-nav.js\n");
    exit(1);
}

echo "Actual #discord-logout-nav now performs a real page navigation.\n";
?>
PHP

echo
echo "===== BUMP NAV CACHE ON SITE PAGES ====="
for f in *.html; do
    [ -f "$f" ] || continue

    if grep -q 'discord-nav\.js' "$f"; then
        cp -a "$f" "$f.before_actual_logout_cache_$STAMP"

        php -r '
            $f=$argv[1];
            $s=file_get_contents($f);
            $s=preg_replace(
                "~discord-nav\\.js(?:\\?v=[^\"'\''[:space:]<>]+)?~",
                "discord-nav.js?v=20261015-drive-logout-3",
                $s
            );
            file_put_contents($f,$s);
        ' "$f"
    fi
done

echo
echo "===== VERIFY ====="
echo "--- Actual logout element logic ---"
grep -n -A20 -B2 "NERDHALLA ACTUAL LOGOUT NAV V3" "$NAV" | head -35

echo
echo "--- Wrong old targets should be gone ---"
if grep -q "NERDHALLA FORCE LOGOUT NAV V1\|NERDHALLA FORCE LOGOUT NAV V2" "$NAV"; then
    echo "WARNING: an old logout patch is still present."
else
    echo "Old incorrect logout patches removed."
fi

echo
echo "--- Drive redirect ---"
grep -n -A5 -B2 "NERDHALLA DRIVE LOGOUT CHAIN" "$LOGOUT"

echo
echo "--- Account nav cache version ---"
grep -n "discord-nav.js?v=20261015-drive-logout-3" account.html | head -3

echo
echo "===== DONE ====="
