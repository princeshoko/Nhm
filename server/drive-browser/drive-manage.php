<?php
declare(strict_types=1);

require __DIR__ . '/discord-auth-common.php';

nerdhalla_start_session();

if (!isset($_SESSION['discord_user']) || !is_array($_SESSION['discord_user'])) {
    header('Location: /discord-login.php', true, 302);
    exit;
}

$config = nerdhalla_oauth_config();
if ($config === null || empty($config['sharla_api_secret'])) {
    http_response_code(503);
    header('Content-Type: text/plain; charset=utf-8');
    echo "Nerdhalla Drive login is temporarily unavailable.\n";
    exit;
}

$userId = (string)($_SESSION['discord_user']['id'] ?? '');
if ($userId === '' || !ctype_digit($userId)) {
    http_response_code(400);
    header('Content-Type: text/plain; charset=utf-8');
    echo "Invalid Discord session.\n";
    exit;
}

function nh_drive_b64url(string $value): string {
    return rtrim(strtr(base64_encode($value), '+/', '-_'), '=');
}

$now = time();
$payload = array(
    'kind' => 'manage',
    'uid' => $userId,
    'iat' => $now,
    'exp' => $now + 3600,
    'nonce' => bin2hex(random_bytes(16)),
);

$payloadJson = json_encode($payload, JSON_UNESCAPED_SLASHES);
if (!is_string($payloadJson)) {
    http_response_code(500);
    exit;
}

$payloadB64 = nh_drive_b64url($payloadJson);
$signingInput = 'v1.' . $payloadB64;
$signature = hash_hmac('sha256', $signingInput, (string)$config['sharla_api_secret']);
$token = $signingInput . '.' . $signature;

header('Cache-Control: no-store, private');
header('Referrer-Policy: no-referrer');
header('Location: https://drive.nerdhalla.net/manage/#token=' . rawurlencode($token), true, 302);
exit;