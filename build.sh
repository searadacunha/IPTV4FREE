#!/usr/bin/env bash
# Builds the WATCHED APK without Gradle: aapt2, javac, d8, zipalign, apksigner.
# Output: build/watched-1.0.apk, signed with the stable key (see SIGNING below) or the computer's Android debug key.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/${BUILD_TOOLS:-36.0.0}
JAR=$SDK/platforms/android-${PLATFORM:-36}/android.jar
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export PATH=$JAVA_HOME/bin:$PATH
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex
# The APKs installed on the TV are signed by a stable key kept outside the repository (an update signed by
# another key is refused by Android). Without that file (CI), the computer's debug key is used.
SIGNING=${S4F_SIGNING:-$HOME/.config/streaming4free/signing/signing.properties}
if [[ -f $SIGNING ]]; then
  prop() { sed -n "s/^$1=//p" "$SIGNING"; }
  KS=$(prop storeFile) ALIAS=$(prop keyAlias)
  export S4F_KS_PASS=$(prop storePassword) S4F_KEY_PASS=$(prop keyPassword)
else
  KS=$HOME/.android/debug.keystore ALIAS=androiddebugkey
  export S4F_KS_PASS=android S4F_KEY_PASS=android
  if [[ ! -f $KS ]]; then  # same key Android Studio would create
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass android -alias androiddebugkey -keypass android \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
  fi
fi
$BT/aapt2 compile --dir res -o $OUT/res.zip
# DEBUG=1: debuggable app (WebView DevTools over ADB), for testing only
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -o $OUT/unsigned.apk $OUT/res.zip --auto-add-overlay ${DEBUG:+--debug-mode}
javac -source 8 -target 8 -Xlint:-options -bootclasspath $JAR -d $OUT/classes src/com/local/watchedtv/*.java
$BT/d8 --release --min-api 21 --lib $JAR --output $OUT/dex $OUT/classes/com/local/watchedtv/*.class
(cd $OUT/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
$BT/apksigner sign --ks "$KS" --ks-pass env:S4F_KS_PASS --ks-key-alias "$ALIAS" --key-pass env:S4F_KEY_PASS \
  --out $OUT/watched-1.0.apk $OUT/aligned.apk
$BT/apksigner verify $OUT/watched-1.0.apk
ls -l $OUT/watched-1.0.apk
