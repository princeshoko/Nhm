#!/usr/bin/env bash
# Nerdhalla: pull latest verified public GitHub APK release onto Hydra.
# Default is read-only (--check); cron calls --install.
set -euo pipefail
umask 077

MODE="${1:---check}"
if [[ "$MODE" != "--check" && "$MODE" != "--install" ]]; then
  echo "Usage: $0 [--check|--install]" >&2
  exit 2
fi

BRIDGE="$HOME/.chatgpt-bridge"
LIVE="$HOME/nerdhalla.net"
APK="$LIVE/nerdhalla-latest.apk"
INFO="$LIVE/apk-build.json"
BACKUPS="$BRIDGE/apk-backups"

test -d "$BRIDGE" && test -d "$LIVE"
for utility in curl php sha256sum flock stat mktemp; do
  command -v "$utility" >/dev/null || { echo "Missing utility: $utility" >&2; exit 1; }
done
test ! -L "$APK" && test ! -L "$INFO"

mkdir -p "$BACKUPS"
exec 9>"$BRIDGE/.nerdhalla-apk-sync.lock"
flock -n 9 || exit 0

TMP="$(mktemp -d "$BRIDGE/apk-sync.XXXXXXXX")"
STAGED_APK="$LIVE/.nerdhalla-latest.apk.$$.tmp"
STAGED_INFO="$LIVE/.apk-build.json.$$.tmp"
trap 'rm -rf "$TMP"; rm -f "$STAGED_APK" "$STAGED_INFO"' EXIT

CURL=(curl -fsSL --proto '=https' --tlsv1.2 --retry 3 --connect-timeout 15 --max-time 240)
"${CURL[@]}" -H 'Accept: application/vnd.github+json' \
  -H 'User-Agent: Nerdhalla-Hydra-APK-Sync' \
  'https://api.github.com/repos/princeshoko/Nhm/releases/latest' > "$TMP/release.json"

# Require both assets from the SAME immutable GitHub release.
php -r '$r = json_decode(file_get_contents($argv[1]), true); if (!is_array($r) || !preg_match("/^apk-[0-9]+-[0-9]+$/", $r["tag_name"] ?? "") || ($r["draft"] ?? true) !== false || ($r["prerelease"] ?? true) !== false) { fwrite(STDERR, "Invalid/latest GitHub release unavailable\n"); exit(1); } $tag = $r["tag_name"]; $found = []; foreach (($r["assets"] ?? []) as $a) { if (is_array($a) && isset($a["name"], $a["browser_download_url"])) { $found[$a["name"]] = $a["browser_download_url"]; } } foreach (["apk-build.json", "nerdhalla-latest.apk"] as $name) { $expected = "https://github.com/princeshoko/Nhm/releases/download/$tag/$name"; if (($found[$name] ?? "") !== $expected) { fwrite(STDERR, "Missing/unexpected release asset: $name\n"); exit(1); } echo $expected, "\n"; } echo $tag, "\n";' "$TMP/release.json" > "$TMP/links"

mapfile -t LINKS < "$TMP/links"
META_URL="${LINKS[0]}"
APK_URL="${LINKS[1]}"
TAG="${LINKS[2]}"

"${CURL[@]}" "$META_URL" -o "$TMP/apk-build.json"

php -r '$a = json_decode(file_get_contents($argv[1]), true); if (!is_array($a) || !is_int($a["build"] ?? null) || $a["build"] < 1 || !is_int($a["size_bytes"] ?? null) || $a["size_bytes"] < 50000 || $a["size_bytes"] > 150000000 || !is_int($a["run_id"] ?? null) || !is_int($a["run_attempt"] ?? null) || "apk-" . $a["run_id"] . "-" . $a["run_attempt"] !== $argv[2] || !is_string($a["sha256"] ?? null) || !preg_match("/^[a-f0-9]{64}$/", $a["sha256"]) || !is_string($a["version"] ?? null) || !preg_match("/^[A-Za-z0-9._+-]{1,64}$/", $a["version"])) { fwrite(STDERR, "Invalid APK build metadata\n"); exit(1); } echo $a["build"], "\n", $a["version"], "\n", $a["sha256"], "\n", $a["size_bytes"], "\n";' "$TMP/apk-build.json" "$TAG" > "$TMP/values"

mapfile -t VALUES < "$TMP/values"
BUILD="${VALUES[0]}"
VERSION="${VALUES[1]}"
SHA="${VALUES[2]}"
SIZE="${VALUES[3]}"

if [[ -f "$INFO" ]]; then
  EXISTING_BUILD="$(php -r '$a=json_decode(file_get_contents($argv[1]), true); echo is_int($a["build"] ?? null) ? $a["build"] : 0;' "$INFO")"
  if (( BUILD < EXISTING_BUILD )); then
    echo "STOP: Refusing downgrade from $EXISTING_BUILD to $BUILD" >&2
    exit 1
  fi
fi

echo "Latest GitHub release: $TAG"
echo "APK version: $VERSION / build: $BUILD"
echo "APK bytes: $SIZE"
echo "Expected SHA256: $SHA"

if [[ "$MODE" == "--check" ]]; then
  if [[ -f "$APK" ]]; then
    echo "Current server APK bytes: $(stat -c %s "$APK")"
    echo "Current server APK SHA256: $(sha256sum "$APK" | cut -d' ' -f1)"
  else
    echo "Current server APK: missing"
  fi
  echo "Read-only check complete; nothing installed."
  exit 0
fi

if [[ -f "$APK" && -f "$INFO" ]] && cmp -s "$TMP/apk-build.json" "$INFO" \
   && printf '%s  %s\n' "$SHA" "$APK" | sha256sum -c --status; then
  exit 0
fi

"${CURL[@]}" --max-filesize 150000000 "$APK_URL" -o "$TMP/new.apk"
test "$(stat -c %s "$TMP/new.apk")" = "$SIZE" || {
  echo "STOP: Downloaded APK size mismatch" >&2; exit 1;
}
printf '%s  %s\n' "$SHA" "$TMP/new.apk" | sha256sum -c - || {
  echo "STOP: Downloaded APK SHA256 mismatch" >&2; exit 1;
}

STAMP="$(date +%Y%m%d_%H%M%S)"
if [[ -f "$APK" ]]; then
  cp -p "$APK" "$BACKUPS/nerdhalla-latest-$STAMP.apk"
fi
if [[ -f "$INFO" ]]; then
  cp -p "$INFO" "$BACKUPS/apk-build-$STAMP.json"
fi

install -m 644 "$TMP/new.apk" "$STAGED_APK"
install -m 644 "$TMP/apk-build.json" "$STAGED_INFO"
mv -f "$STAGED_APK" "$APK"
mv -f "$STAGED_INFO" "$INFO"

printf '%s  %s\n' "$SHA" "$APK" | sha256sum -c -
cmp -s "$TMP/apk-build.json" "$INFO"
echo "INSTALLED: Android $VERSION (build $BUILD)"
echo "Website APK: $APK"
echo "Build metadata: $INFO"
