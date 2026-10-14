#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="/home/purplepe/nerdhalla.net"
LOGOUT="$ROOT/discord-logout.php"
STAMP="$(date +%Y%m%d_%H%M%S)"
MARKER="NERDHALLA SERVER SIDE DRIVE REVOKE V1"

cd "$ROOT"

echo "===== BACKUP ====="
cp -a "$LOGOUT" "$LOGOUT.before_drive_server_revoke_$STAMP"

echo
echo "===== PATCH SERVER-SIDE DRIVE REVOKE ====="
php <<'PHP'
<?php
$file = 'discord-logout.php';
$s = file_get_contents($file);
if ($s === false) {
    fwrite(STDERR, "Could not read discord-logout.php\n");
    exit(1);
}

$marker = 'NERDHALLA SERVER SIDE DRIVE REVOKE V1';
if (strpos($s, $marker) !== false) {
    echo "Server-side Drive revoke already installed.\n";
    exit(0);
}

$needle = 'session_destroy();';
$count = substr_count($s, $needle);
if ($count !== 1) {
    fwrite(STDERR, "Expected exactly one session_destroy(); but found $count. No changes made.\n");
    exit(1);
}

$block = <<<'CODE'
/* ===== NERDHALLA SERVER SIDE DRIVE REVOKE V1 ===== */
try {
    $driveUserId = '';
    if (isset($_SESSION['discord_user']) && is_array($_SESSION['discord_user'])) {
        $driveUserId = isset($_SESSION['discord_user']['id'])
            ? preg_replace('/[^0-9]/', '', (string)$_SESSION['discord_user']['id'])
            : '';
    }

    $driveConfig = function_exists('nerdhalla_oauth_config')
        ? nerdhalla_oauth_config()
        : null;

    if (
        $driveUserId !== '' &&
        is_array($driveConfig) &&
        !empty($driveConfig['sharla_api_url']) &&
        !empty($driveConfig['sharla_api_secret']) &&
        function_exists('curl_init')
    ) {
        $driveMethod = 'POST';
        $drivePath = '/portal/me/drive/browser-revoke';
        $driveBody = '{}';
        $driveTimestamp = (string)time();
        $driveBodyHash = hash('sha256', $driveBody);

        $driveMessage =
            $driveTimestamp . "\n" .
            $driveUserId . "\n" .
            $driveMethod . "\n" .
            $drivePath . "\n" .
            $driveBodyHash;

        $driveSignature = hash_hmac(
            'sha256',
            $driveMessage,
            (string)$driveConfig['sharla_api_secret']
        );

        $driveUrl =
            rtrim((string)$driveConfig['sharla_api_url'], '/') .
            $drivePath;

        $driveCurl = curl_init($driveUrl);
        curl_setopt_array($driveCurl, array(
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_CONNECTTIMEOUT => 3,
            CURLOPT_TIMEOUT => 6,
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => $driveBody,
            CURLOPT_HTTPHEADER => array(
                'Accept: application/json',
                'Content-Type: application/json',
                'X-Sharla-Portal-User: ' . $driveUserId,
                'X-Sharla-Portal-Timestamp: ' . $driveTimestamp,
                'X-Sharla-Portal-Signature: ' . $driveSignature
            ),
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2
        ));

        $driveResponse = curl_exec($driveCurl);
        $driveStatus = (int)curl_getinfo($driveCurl, CURLINFO_HTTP_CODE);
        $driveError = curl_error($driveCurl);
        curl_close($driveCurl);

        if ($driveStatus < 200 || $driveStatus >= 300) {
            error_log(
                'Nerdhalla Drive logout revoke failed for user ' .
                $driveUserId .
                ' HTTP ' . $driveStatus .
                ($driveError !== '' ? ' curl=' . $driveError : '')
            );
        }
    }
} catch (Throwable $driveLogoutError) {
    error_log('Nerdhalla Drive logout revoke exception: ' . $driveLogoutError->getMessage());
}
/* ===== END NERDHALLA SERVER SIDE DRIVE REVOKE V1 ===== */

CODE;

$s = str_replace($needle, $block . $needle, $s);

if (file_put_contents($file, $s) === false) {
    fwrite(STDERR, "Could not write discord-logout.php\n");
    exit(1);
}

echo "Server-side Drive revoke installed.\n";
?>
PHP

echo
echo "===== VERIFY PHP ====="
php -l "$LOGOUT"

echo
echo "===== VERIFY PATCH ====="
grep -n -A12 -B2 "$MARKER" "$LOGOUT" | head -35

echo
echo "===== DONE ====="
echo "Nerdhalla logout now revokes Drive browser tokens server-side before destroying the login session."
