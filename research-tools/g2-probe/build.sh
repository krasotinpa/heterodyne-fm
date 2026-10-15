#!/usr/bin/env bash
# Builds the G2 probe APK without Gradle. Toolchain: JDK 17, platforms;android-30, build-tools;34.0.0.
# Usage: research-tools/g2-probe/build.sh   ->  research-tools/g2-probe/build/super-radio-probe.apk
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-$HOME/opt/jdk}
export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_HOME:-$HOME/opt/android-sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-30/android.jar
KEY=${PROBE_KEYSTORE:-$HOME/.android/super-radio-probe.jks}
"$JAVA_HOME/bin/javac" -version 2>&1 | grep -q ' 17\.' || { echo "need JDK 17 in JAVA_HOME" >&2; exit 1; }
rm -rf build && mkdir -p build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/unsigned.apk build/res.zip
# ponytail: --release 8 checks java.* against JDK 8, not android.jar; fine for this one-file probe.
"$JAVA_HOME/bin/javac" --release 8 -nowarn -classpath "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
python3 -c "import zipfile; zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED).write('build/dex/classes.dex', 'classes.dex')"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KEY" ]; then
  mkdir -p "$(dirname "$KEY")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEY" -storepass android -keypass android -alias probe \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Super Radio Probe" >/dev/null
fi
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --out build/super-radio-probe.apk build/aligned.apk

# Check: the APK carries the expected package, SDK levels and the optional USB host feature.
badging=$("$BT/aapt2" dump badging build/super-radio-probe.apk)
for want in "package: name='io.github.krasotinpa.superradio.probe'" "sdkVersion:'26'" "targetSdkVersion:'30'" \
            "uses-feature-not-required: name='android.hardware.usb.host'"; do
  grep -qF "$want" <<<"$badging" || { echo "missing in APK: $want" >&2; exit 1; }
done
"$BT/apksigner" verify build/super-radio-probe.apk
sha256sum build/super-radio-probe.apk
