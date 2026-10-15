#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="/home/purplepe/nerdhalla.net"
FILE="$ROOT/account.html"
STAMP="$(date +%Y%m%d_%H%M%S)"
MARKER="NERDHALLA DRIVE READY STATUS V1"

cd "$ROOT" || exit 1

if [ ! -f "$FILE" ]; then
    echo "ERROR: $FILE not found."
    exit 1
fi

echo "===== BACKUP ====="
cp -p "$FILE" "$FILE.before_drive_ready_status_$STAMP"
echo "$FILE.before_drive_ready_status_$STAMP"

echo
echo "===== INSTALL DRIVE READY STATUS ====="

php <<'PHP'
<?php
$file = '/home/purplepe/nerdhalla.net/account.html';
$s = file_get_contents($file);

if ($s === false) {
    fwrite(STDERR, "Could not read account.html\n");
    exit(1);
}

$marker = 'NERDHALLA DRIVE READY STATUS V1';

$block = <<<'HTML'

<!-- NERDHALLA DRIVE READY STATUS V1 -->
<style>
.nh-drive-ready-status{
  display:inline-flex;
  align-items:center;
  min-height:34px;
  padding:6px 10px;
  border:1px solid rgba(126,231,135,.28);
  border-radius:10px;
  background:rgba(126,231,135,.08);
  color:#baf5c0;
  font-size:12px;
  font-weight:800;
  line-height:1.2;
  white-space:normal;
}
</style>
<script>
(function(){
  function patchDriveWebButton(){
    const roots = Array.from(document.querySelectorAll(
      '[data-sharla-panel="drive"], #drive, #drive-panel, .drive-panel'
    ));

    roots.forEach(root => {
      root.querySelectorAll('a,button').forEach(node => {
        const label = String(node.textContent || '')
          .replace(/\s+/g,' ')
          .trim();

        if (/^(?:🌐\s*)?web$/i.test(label)) {
          const status = document.createElement('span');
          status.className = 'nh-drive-ready-status';
          status.textContent = 'Your Nerdhalla Drive is ready';
          status.setAttribute('role','status');
          node.replaceWith(status);
        }
      });
    });
  }

  function start(){
    patchDriveWebButton();

    const observer = new MutationObserver(() => {
      patchDriveWebButton();
    });

    observer.observe(document.body || document.documentElement, {
      childList:true,
      subtree:true
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, {once:true});
  } else {
    start();
  }
})();
</script>
<!-- END NERDHALLA DRIVE READY STATUS V1 -->

HTML;

$pattern = '~\n?<!-- NERDHALLA DRIVE READY STATUS V1 -->.*?<!-- END NERDHALLA DRIVE READY STATUS V1 -->\n?~s';
$s = preg_replace($pattern, "\n", $s);

if ($s === null) {
    fwrite(STDERR, "Could not remove prior Drive status block.\n");
    exit(1);
}

$pos = stripos($s, '</body>');
if ($pos === false) {
    fwrite(STDERR, "No </body> found in account.html\n");
    exit(1);
}

$s = substr($s, 0, $pos) . $block . substr($s, $pos);

if (file_put_contents($file, $s) === false) {
    fwrite(STDERR, "Could not write account.html\n");
    exit(1);
}

echo "Updated account.html\n";
?>
PHP

echo
echo "===== VERIFY SOURCE ====="
grep -n "NERDHALLA DRIVE READY STATUS V1\|Your Nerdhalla Drive is ready" account.html

echo
echo "===== LIVE PAGE ====="
curl -sS -o /dev/null -w "account.html HTTP: %{http_code}\n" https://nerdhalla.net/account.html

echo
echo "===== DONE ====="
