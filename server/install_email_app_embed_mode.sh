#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="/home/purplepe/nerdhalla.net"
STAMP="$(date +%Y%m%d_%H%M%S)"

cd "$ROOT" || exit 1

for FILE in email.html webmail.html; do
    if [ ! -f "$FILE" ]; then
        echo "ERROR: $FILE not found in $ROOT"
        return
    fi
    cp -p "$FILE" "$FILE.before_app_embed_$STAMP"
done

php <<'PHP'
<?php
$files = ['email.html', 'webmail.html'];

$block = <<<'HTML'

<!-- NERDHALLA APP EMBED MODE V1 -->
<script>
(function(){
  try{
    if(new URLSearchParams(window.location.search).get('app') === '1'){
      document.documentElement.classList.add('nerdhalla-app-embed');
    }
  }catch(e){}
})();
</script>
<style>
html.nerdhalla-app-embed,
html.nerdhalla-app-embed body{
  margin:0 !important;
  padding:0 !important;
  min-height:100% !important;
  background:#0b0811 !important;
}
html.nerdhalla-app-embed header.topbar,
html.nerdhalla-app-embed .topbar,
html.nerdhalla-app-embed footer.nerdhalla-site-footer,
html.nerdhalla-app-embed .nerdhalla-site-footer{
  display:none !important;
}
html.nerdhalla-app-embed main.wrap,
html.nerdhalla-app-embed .wrap{
  width:100% !important;
  max-width:none !important;
  margin:0 !important;
  padding:10px 12px 24px !important;
  box-sizing:border-box !important;
}
html.nerdhalla-app-embed main{
  margin-top:0 !important;
}
</style>
<!-- END NERDHALLA APP EMBED MODE V1 -->

HTML;

foreach ($files as $file) {
    $s = file_get_contents($file);
    if ($s === false) {
        fwrite(STDERR, "Could not read $file\n");
        exit(1);
    }

    if (strpos($s, 'NERDHALLA APP EMBED MODE V1') !== false) {
        echo "Already installed: $file\n";
        continue;
    }

    $pos = stripos($s, '</head>');
    if ($pos === false) {
        fwrite(STDERR, "No </head> found in $file\n");
        exit(1);
    }

    $s = substr($s, 0, $pos) . $block . substr($s, $pos);

    if (file_put_contents($file, $s) === false) {
        fwrite(STDERR, "Could not write $file\n");
        exit(1);
    }

    echo "Updated: $file\n";
}
PHP

echo
echo "===== VERIFY APP MODE ====="
grep -n "NERDHALLA APP EMBED MODE V1" email.html webmail.html

echo
echo "===== NORMAL PAGE HTTP ====="
curl -sS -o /dev/null -w "email.html HTTP: %{http_code}\n" https://nerdhalla.net/email.html
curl -sS -o /dev/null -w "webmail.html HTTP: %{http_code}\n" https://nerdhalla.net/webmail.html

echo
echo "===== APP MODE HTTP ====="
curl -sS -o /dev/null -w "email.html?app=1 HTTP: %{http_code}\n" 'https://nerdhalla.net/email.html?app=1'
curl -sS -o /dev/null -w "webmail.html?app=1 HTTP: %{http_code}\n" 'https://nerdhalla.net/webmail.html?app=1'

echo
echo "===== DONE ====="
