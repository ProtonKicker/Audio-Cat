#!/usr/bin/env bash
#
# Generates the local signing keystore used by both the debug and release
# variants. The key never leaves this project and is not a secret worth
# protecting: it only exists so that Gradle does not need a writable ~/.android.
#
# Usage: scripts/make-keystore.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/keystore"
STORE="$DIR/audiocat.jks"
PROPS="$DIR/keystore.properties"

if [[ -f "$STORE" && -f "$PROPS" ]]; then
    echo "keystore already present: $STORE"
    exit 0
fi

mkdir -p "$DIR"

PASS="audiocat"
ALIAS="audiocat"

keytool -genkeypair \
    -keystore "$STORE" \
    -storetype JKS \
    -storepass "$PASS" \
    -keypass "$PASS" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Audio Cat, OU=Local, O=Audio Cat, L=Local, ST=Local, C=US" \
    -noprompt >/dev/null

cat > "$PROPS" <<EOF
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF

chmod 600 "$PROPS"
echo "created $STORE"
