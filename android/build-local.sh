#!/usr/bin/env bash
#
# build-local.sh — build dell'APK di Homeworld in locale.
#
# Prepara l'ambiente e compila l'APK (release di default). Idempotente:
# inizializza i submodule, crea local.properties e genera la keystore di
# fallback solo se mancano. Variabili d'ambiente sovrascrivibili:
#
#   JAVA_HOME            JDK da usare (default: autodetect, serve JDK 17+)
#   ANDROID_HOME         Android SDK   (default: ~/Android/Sdk)
#   ANDROID_KEYSTORE_*   credenziali firma (vedi app/build.gradle)
#
# Uso:
#   ./build-local.sh            # assembleRelease (APK firmato)
#   ./build-local.sh debug      # assembleDebug
#   ./build-local.sh <task>     # qualsiasi task gradle
#
set -euo pipefail

# Directory di questo script (= android/) e root del repo.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- JDK -------------------------------------------------------------------
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/javac" ]]; then
    for candidate in \
        /usr/lib/jvm/java-17-openjdk \
        /usr/lib/jvm/java-21-openjdk \
        /usr/lib/jvm/default; do
        if [[ -x "$candidate/bin/javac" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
    echo "ERRORE: nessun JDK trovato. Installa un JDK 17+ (es. 'sudo pacman -S jdk17-openjdk') o esporta JAVA_HOME." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

# --- Android SDK -----------------------------------------------------------
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ ! -d "$ANDROID_HOME/platform-tools" ]]; then
    echo "ERRORE: Android SDK non trovato in '$ANDROID_HOME'." >&2
    echo "        Imposta ANDROID_HOME oppure installa cmdline-tools + platform-34," >&2
    echo "        build-tools;34.0.0, ndk;27.3.13750724, cmake;3.22.1 via sdkmanager." >&2
    exit 1
fi

# --- local.properties (per Gradle) ----------------------------------------
if [[ ! -f "$SCRIPT_DIR/local.properties" ]]; then
    echo "==> Creo local.properties -> sdk.dir=$ANDROID_HOME"
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$SCRIPT_DIR/local.properties"
fi

# --- Submodule nativi (SDL, gl4es) ----------------------------------------
if [[ ! -f "$SCRIPT_DIR/app/jni/SDL/CMakeLists.txt" || \
      ! -f "$SCRIPT_DIR/app/jni/gl4es/CMakeLists.txt" ]]; then
    echo "==> Inizializzo i submodule git (SDL, gl4es)..."
    git -C "$REPO_ROOT" submodule update --init --recursive
fi

# --- Keystore di firma (solo per build release) ----------------------------
KEYSTORE_PATH="${ANDROID_KEYSTORE_PATH:-$SCRIPT_DIR/release.keystore}"
STORE_PASS="${ANDROID_KEYSTORE_PASSWORD:-gokgokgok}"
KEY_ALIAS="${ANDROID_KEY_ALIAS:-gok}"
KEY_PASS="${ANDROID_KEY_PASSWORD:-gokgokgok}"

GRADLE_TASK="assembleRelease"
case "${1:-}" in
    ""|release) GRADLE_TASK="assembleRelease" ;;
    debug)      GRADLE_TASK="assembleDebug" ;;
    *)          GRADLE_TASK="$1" ;;
esac

if [[ "$GRADLE_TASK" == *Release* && ! -f "$KEYSTORE_PATH" ]]; then
    echo "==> Genero la keystore di fallback (self-signed, uso personale): $KEYSTORE_PATH"
    "$JAVA_HOME/bin/keytool" -genkeypair -v \
        -keystore "$KEYSTORE_PATH" \
        -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Homeworld, OU=Personal, O=Homeworld, C=IT"
fi

# --- Build -----------------------------------------------------------------
echo "==> JAVA_HOME=$JAVA_HOME"
echo "==> ANDROID_HOME=$ANDROID_HOME"
echo "==> gradle task: $GRADLE_TASK"
cd "$SCRIPT_DIR"
chmod +x ./gradlew
./gradlew "$GRADLE_TASK"

# --- Riepilogo output ------------------------------------------------------
echo
echo "Build completata. APK prodotti:"
find "$SCRIPT_DIR/app/build/outputs/apk" -name '*.apk' -printf '  %p (%k KB)\n' 2>/dev/null || true
