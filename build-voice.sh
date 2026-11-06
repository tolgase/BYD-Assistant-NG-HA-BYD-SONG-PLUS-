#!/usr/bin/env bash
# Builds out/byd-assistant-songplus.apk with Docker only (no Android Studio, no GitHub).
# First run: downloads Gradle, the Android SDK parts and the libraries (~2 GB, 10-20 min). Later runs are quick.
# Keep the signing/ folder: every update must be signed with the same key or Android refuses to install it.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p signing out

docker build -q -f voice-builder.Dockerfile -t byd-voice-builder . >/dev/null

if [ ! -f signing/voice.keystore ]; then
  PW=$(openssl rand -hex 16)
  docker run --rm -v "$PWD/signing:/s" byd-voice-builder keytool -genkeypair -storetype PKCS12 -keystore /s/voice.keystore \
    -alias voice -keyalg RSA -keysize 2048 -validity 10000 -storepass "$PW" -keypass "$PW" \
    -dname "CN=BYD Assistant NG Song PLUS" >/dev/null 2>&1
  echo "$PW" > signing/voice.pass
  chmod 600 signing/*
  echo "Created a signing key in signing/ — back it up."
fi
PW=$(cat signing/voice.pass)
printf 'KEY_ALIAS=voice\nKEY_PASSWORD=%s\nSTORE_FILE=../signing/voice.keystore\n' "$PW" > app/signing.properties
chmod 600 app/signing.properties

# The project reads git tags for beta numbering; give it an empty repository.
[ -d .git ] || docker run --rm -v "$PWD:/src" byd-voice-builder git init -q /src

docker run --rm -v "$PWD:/src" -v byd-voice-gradle:/root/.gradle -v byd-voice-sdk:/opt/android-sdk byd-voice-builder \
  bash -c 'chmod +x gradlew && ./gradlew --no-daemon --console=plain assembleRelease'

APK=$(ls -t app/build/outputs/apk/release/*.apk | head -n1)
cp "$APK" out/byd-assistant-songplus.apk
echo "APK: $PWD/out/byd-assistant-songplus.apk"
