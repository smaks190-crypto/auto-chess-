#!/usr/bin/env bash
set -euo pipefail
umask 077
keydir="${NOX_DEV_SIGNING_DIR:-$HOME/.nox-dev-signing}"
store="$keydir/nox-dev.p12"
pass="$keydir/password"
fingerprint="$keydir/certificate.sha256"
if [[ "${1:-}" == "--init" && $# == 1 ]]; then
  mkdir -p "$keydir"
  chmod 700 "$keydir"
  if [[ -e "$store" || -e "$pass" || -e "$fingerprint" ]]; then
    echo "Signing identity already exists or initialization was interrupted; refusing to overwrite." >&2
    exit 1
  fi
  python -c 'import secrets; print(secrets.token_hex(32))' > "$pass"
  keytool -genkeypair -storetype PKCS12 -keystore "$store" -alias nox-dev -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=N.O.X. Dev" -storepass:file "$pass" -keypass:file "$pass"
  keytool -exportcert -keystore "$store" -alias nox-dev -storepass:file "$pass" | sha256sum | cut -d " " -f 1 > "$fingerprint"
  cat "$fingerprint"
  exit 0
fi
if [[ $# != 2 ]]; then
  echo "Usage: bash sign-nox-dev.sh --init | INPUT_DEV_APK OUTPUT_APK" >&2
  exit 2
fi
[[ -s "$store" && -s "$pass" && -s "$fingerprint" ]] || { echo "Persistent signing identity missing. Do not initialize again if N.O.X. Dev is already installed." >&2; exit 1; }
[[ -f "$1" && ! -e "$2" ]] || { echo "Input APK missing or output already exists." >&2; exit 1; }
tmp="$(mktemp "${2}.signing.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
apksigner sign --ks "$store" --ks-key-alias nox-dev --ks-pass "file:$pass" --v4-signing-enabled false --out "$tmp" "$1"
verified="$(apksigner verify --verbose --print-certs "$tmp")"
actual="$(printf "%s\n" "$verified" | sed -n "s/^Signer #1 certificate SHA-256 digest: //p")"
count="$(printf "%s\n" "$verified" | grep -c "^Signer #[0-9][0-9]* certificate SHA-256 digest:" || true)"
[[ "$count" == 1 && "$actual" == "$(cat "$fingerprint")" ]] || { echo "Unexpected signing certificate." >&2; exit 1; }
mv "$tmp" "$2"
printf "Verified signer: %s\n" "$actual"
