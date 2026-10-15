#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="/home/purplepe/nerdhalla.net"
LOGOUT="$ROOT/discord-logout.php"
NAV="$ROOT/discord-nav.js"
STAMP="$(date +%Y%m%d_%H%M%S)"
MARKER="NERDHALLA DRIVE-FIRST LOGOUT V4"

cd "$ROOT"

echo "===== BACKUP ====="
cp -a "$LOGOUT" "$LOGOUT.before_drive_first_logout_$STAMP"
[ ! -f "$NAV" ] || cp -a "$NAV" "$NAV.before_drive_first_logout_$STAMP"

echo
echo "===== CLEAN OLD LOGOUT PATCHES ====="
php <<'PHP'
<?php
$file = 'discord-logout.php';
$s = file_get_contents($file);
if ($s === false) {
    fwrite(STDERR, "Could not read discord-logout.php\n");
    exit(1);
}

/* The browser now visits Drive FIRST, so remove old post-logout Drive chaining. */
$s = preg_replace(
    '~\s*/\* NERDHALLA DRIVE LOGOUT CHAIN \*/.*?/\* END NERDHALLA DRIVE LOGOUT CHAIN \*/\s*~s',
    "\n",
    $s
);

/* The Drive-first browser hop makes the older Sharla revoke shim unnecessary. */
$s = preg_replace(
    '~\s*/\* ===== NERDHALLA SERVER SIDE DRIVE REVOKE V1 ===== \*/.*?/\* ===== END NERDHALLA SERVER SIDE DRIVE REVOKE V1 ===== \*/\s*~s',
    "\n",
    $s
);

file_put_contents($file, $s);
?>
PHP

php -l "$LOGOUT"

echo
echo "===== REMOVE OLD NAV OVERRIDES ====="
if [ -f "$NAV" ]; then
php <<'PHP'
<?php
$file = 'discord-nav.js';
$s = file_get_contents($file);
if ($s === false) exit(1);

$patterns = array(
    '~\s*/\* ===== NERDHALLA FORCE LOGOUT NAV V1 ===== \*/.*?/\* ===== END NERDHALLA FORCE LOGOUT NAV V1 ===== \*/\s*~s',
    '~\s*/\* ===== NERDHALLA FORCE LOGOUT NAV V2 ===== \*/.*?/\* ===== END NERDHALLA FORCE LOGOUT NAV V2 ===== \*/\s*~s',
    '~\s*/\* ===== NERDHALLA ACTUAL LOGOUT NAV V3 ===== \*/.*?/\* ===== END NERDHALLA ACTUAL LOGOUT NAV V3 ===== \*/\s*~s'
);

foreach ($patterns as $pattern) {
    $s = preg_replace($pattern, "\n", $s);
}

file_put_contents($file, $s);
?>
PHP
fi

echo
echo "===== INSTALL DRIVE-FIRST LOGOUT ON SITE PAGES ====="
php <<'PHP'
<?php
$files = glob('*.html');
$marker = 'NERDHALLA DRIVE-FIRST LOGOUT V4';

$script = <<<'HTML'
<script>
/* ===== NERDHALLA DRIVE-FIRST LOGOUT V4 ===== */
(function(){
  'use strict';

  function isLogoutControl(el){
    if (!el) return false;
    var id = String(el.id || '').toLowerCase();
    var href = String(el.getAttribute && el.getAttribute('href') || '').toLowerCase();
    var text = String(el.textContent || '').trim().toLowerCase();

    return id === 'discord-logout-nav' ||
           href.indexOf('discord-logout.php') !== -1 ||
           text === 'logout' ||
           text === 'log out' ||
           text.indexOf('logout') !== -1;
  }

  window.addEventListener('click', function(event){
    var target = event.target;
    var control = target && target.closest ? target.closest('a,button') : null;
    if (!isLogoutControl(control)) return;

    event.preventDefault();
    event.stopPropagation();
    if (typeof event.stopImmediatePropagation === 'function') {
      event.stopImmediatePropagation();
    }

    /* IMPORTANT: visit Drive origin FIRST so nhdrive is actually sent. */
    window.location.assign('https://drive.nerdhalla.net/private/logout');
  }, true);
})();
 /* ===== END NERDHALLA DRIVE-FIRST LOGOUT V4 ===== */
</script>
HTML;

$changed = 0;

foreach ($files as $file) {
    $s = file_get_contents($file);
    if ($s === false) continue;
    if (strpos($s, 'discord-nav.js') === false) continue;

    /* Remove an older V4 block if this installer is re-run. */
    $s = preg_replace(
        '~\s*<script>\s*/\* ===== NERDHALLA DRIVE-FIRST LOGOUT V4 ===== \*/.*?/\* ===== END NERDHALLA DRIVE-FIRST LOGOUT V4 ===== \*/\s*</script>\s*~s',
        "\n",
        $s
    );

    $pos = stripos($s, '</head>');
    if ($pos === false) continue;

    copy($file, $file . '.before_drive_first_logout_' . date('Ymd_His'));
    $s = substr($s, 0, $pos) . "\n" . $script . "\n" . substr($s, $pos);
    file_put_contents($file, $s);
    $changed++;
}

echo "Updated $changed HTML page(s).\n";
?>
PHP

echo
echo "===== VERIFY ====="
echo "--- account.html inline hook ---"
grep -n "$MARKER" account.html | head -5 || true

echo
echo "--- old logout patches ---"
grep -nE 'NERDHALLA (DRIVE LOGOUT CHAIN|SERVER SIDE DRIVE REVOKE)|NERDHALLA (FORCE LOGOUT NAV|ACTUAL LOGOUT NAV)'   "$LOGOUT" "$NAV" 2>/dev/null || echo "Old logout patches removed."

echo
echo "--- logout PHP syntax ---"
php -l "$LOGOUT"

echo
echo "--- current logout redirects ---"
grep -nE "session_destroy|header\(['\"]Location:" "$LOGOUT" || true

echo
echo "===== DONE ====="
echo "Browser logout flow is now: Drive logout -> Nerdhalla logout -> site."
