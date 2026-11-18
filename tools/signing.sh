# Sourced by build.sh and cinefuse/build.sh: picks the key that signs the APK, or stops the build.
# Sets KS and ALIAS, exports S4F_KS_PASS and S4F_KEY_PASS (apksigner reads them with env:, never on the command line).
#
# An APK for the TV must be signed by the release key whose certificate SHA-256 is in signing/release-cert.sha256:
# Android refuses an update signed by another key, and uninstalling first erases the app's favourites and settings.
# The key is named by ~/.config/streaming4free/signing/signing.properties (or $S4F_SIGNING), outside the repository.
# The computer's debug key is only allowed for an explicit test build (S4F_TEST_BUILD=1) or in CI (CI=true).
SIGNING=${S4F_SIGNING:-$HOME/.config/streaming4free/signing/signing.properties}
EXPECTED=$(tr -d ' \r\n' < "$(dirname "${BASH_SOURCE[0]}")/../signing/release-cert.sha256")
if [[ -f $SIGNING ]]; then
  prop() { sed -n "s/^$1=//p" "$SIGNING"; }
  KS=$(prop storeFile) ALIAS=$(prop keyAlias)
  export S4F_KS_PASS=$(prop storePassword) S4F_KEY_PASS=$(prop keyPassword)
  GOT=$(keytool -exportcert -keystore "$KS" -storepass:env S4F_KS_PASS -alias "$ALIAS" | shasum -a 256 | cut -d' ' -f1)
  if [[ $GOT != "$EXPECTED" ]]; then
    echo "Signing stopped: the key in $SIGNING has certificate $GOT, expected $EXPECTED (signing/release-cert.sha256)." >&2
    exit 1
  fi
elif [[ ${S4F_TEST_BUILD:-} == 1 || ${CI:-} == true ]]; then
  echo "Test build: signed with the debug key. It cannot update the apps installed on the TV." >&2
  KS=$HOME/.android/debug.keystore ALIAS=androiddebugkey
  export S4F_KS_PASS=android S4F_KEY_PASS=android
  if [[ ! -f $KS ]]; then  # same key Android Studio would create
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass android -alias androiddebugkey -keypass android \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
  fi
else
  echo "Signing stopped: no release key ($SIGNING). See docs/SIGNING.md; for a test build only: S4F_TEST_BUILD=1." >&2
  exit 1
fi
