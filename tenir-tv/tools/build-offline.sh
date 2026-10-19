#!/usr/bin/env bash
# Сборка APK без Android SDK и Google Maven: только Maven Central + пакеты Ubuntu.
#
# Основной способ сборки — Gradle (./gradlew :app:assembleRelease, нужен доступ к Google Maven).
# Этот скрипт собирает тот же код теми же правилами (minSdk 24, targetSdk 34) там, где Google Maven
# недоступен (закрытый контур, CI без SDK). Нужны: JDK 17+, python3, zip, и пакеты
#   apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23
#
#   tools/build-offline.sh            → dist/tenir-tv-<версия>.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.offline"
CACHE="${TENIR_OFFLINE_CACHE:-$HOME/.cache/tenir-offline}"
MAVEN="${MAVEN_MIRROR:-https://maven-central.storage-download.googleapis.com/maven2}"
KOTLIN=2.0.21
PROGUARD=7.6.1
VERSION_NAME="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$ROOT/app/build.gradle.kts")"
VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$ROOT/app/build.gradle.kts")"
MIN_SDK=24
TARGET_SDK=34
PLATFORM23=/usr/lib/android-sdk/platforms/android-23/android.jar

# локальные утилиты не должны идти через прокси-настройки JVM
unset JAVA_TOOL_OPTIONS || true

mkdir -p "$CACHE"
fetch() { # путь в Maven-репозитории → локальный файл в кеше
  local path="$1" file="$CACHE/$(basename "$1")"
  if [ ! -s "$file" ]; then
    curl -sSfL -o "$file.part" "$MAVEN/$path"
    mv "$file.part" "$file"
  fi
  echo "$file"
}

echo "== зависимости"
# android-all: классы и ресурсы фреймворка Android 14 (API 34), публикуются проектом Robolectric в Maven Central
ANDROID_JAR=$(fetch org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar)
STDLIB=$(fetch org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN/kotlin-stdlib-$KOTLIN.jar)
KCP=""
for a in \
  org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN/kotlin-compiler-embeddable-$KOTLIN.jar \
  org/jetbrains/kotlin/kotlin-script-runtime/$KOTLIN/kotlin-script-runtime-$KOTLIN.jar \
  org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar \
  org/jetbrains/kotlin/kotlin-daemon-embeddable/$KOTLIN/kotlin-daemon-embeddable-$KOTLIN.jar \
  org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar \
  org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar \
  org/jetbrains/annotations/13.0/annotations-13.0.jar; do
  KCP="$KCP:$(fetch "$a")"
done
KCP="$KCP:$STDLIB"
PGCP=""
for a in \
  com/guardsquare/proguard-base/$PROGUARD/proguard-base-$PROGUARD.jar \
  com/guardsquare/proguard-core/9.1.7/proguard-core-9.1.7.jar \
  org/jetbrains/kotlin/kotlin-metadata-jvm/2.0.0/kotlin-metadata-jvm-2.0.0.jar \
  org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.jar \
  org/apache/logging/log4j/log4j-api/2.24.2/log4j-api-2.24.2.jar \
  org/apache/logging/log4j/log4j-core/2.24.2/log4j-core-2.24.2.jar \
  com/google/code/gson/gson/2.11.0/gson-2.11.0.jar; do
  PGCP="$PGCP:$(fetch "$a")"
done

# java.* для компиляции: заглушки из android-23 (не даёт случайно использовать API JDK, которых нет в Android;
# всё, что новее API 23, должно быть под проверкой SDK_INT)
CORE_JAR="$CACHE/core-java-23.jar"
if [ ! -s "$CORE_JAR" ]; then
  tmp=$(mktemp -d); (cd "$tmp" && unzip -q "$PLATFORM23" 'java/*' 'javax/*' && zip -qr "$CORE_JAR" .); rm -rf "$tmp"
fi

rm -rf "$WORK" && mkdir -p "$WORK"/{gen,classes,dex}

echo "== манифест и ресурсы (aapt)"
python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$WORK/AndroidManifest.xml" "$VERSION_CODE" "$VERSION_NAME" $MIN_SDK $TARGET_SDK <<'PY'
import sys
src, dst, code, name, mn, tg = sys.argv[1:]
s = open(src, encoding="utf-8").read()
s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="kz.tenir.tv" '
    f'android:versionCode="{code}" android:versionName="{name}">\n    <uses-sdk android:minSdkVersion="{mn}" android:targetSdkVersion="{tg}" />', 1)
open(dst, "w", encoding="utf-8").write(s)
PY
aapt package -f -m \
  -J "$WORK/gen" -M "$WORK/AndroidManifest.xml" -S "$ROOT/app/src/main/res" -A "$ROOT/app/src/main/assets" \
  -I "$ANDROID_JAR" -F "$WORK/resources.apk" -0 jpg -0 png

echo "== Kotlin $KOTLIN"
java -Xmx2g -cp "${KCP#:}" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-jdk -no-stdlib -no-reflect -jvm-target 1.8 \
  -Xlambdas=class -Xsam-conversions=class -Xjvm-default=disable -Xstring-concat=inline \
  -classpath "$CORE_JAR:$ANDROID_JAR:$STDLIB" \
  -d "$WORK/classes" \
  "$ROOT/core/src/main/kotlin" "$ROOT/app/src/main/java" "$WORK/gen"
javac -nowarn -source 8 -target 8 -bootclasspath "$CORE_JAR" -cp "$ANDROID_JAR" -d "$WORK/classes" \
  $(find "$WORK/gen" -name '*.java') 2>&1 | grep -v -E '^warning|^Note|^1 warning' || true

echo "== ProGuard (сжатие kotlin-stdlib, без обфускации)"
cat > "$WORK/proguard.pro" <<PRO
-injars $WORK/classes
-injars $STDLIB(!META-INF/**)
-outjars $WORK/app.jar
-libraryjars $CORE_JAR
-libraryjars $ANDROID_JAR
-dontobfuscate
-dontoptimize
-dontnote **
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
# Kotlin вызывает Boolean/Integer/Long/Double.hashCode(x) — есть в Android с API 24 (minSdk), нет в заглушках API 23
-dontwarn java.lang.Boolean,java.lang.Integer,java.lang.Long,java.lang.Double
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class kz.tenir.tv.TenirApp { <init>(); }
-keep class * extends android.app.Activity { <init>(); }
-keep class * extends android.content.BroadcastReceiver { <init>(); }
-keep class kz.tenir.tv.R* { *; }
PRO
java -cp "${PGCP#:}" proguard.ProGuard @"$WORK/proguard.pro" | grep -v -E '^Note|^ProGuard' || true

echo "== dex (min-sdk $MIN_SDK)"
dalvik-exchange --dex --min-sdk-version=$MIN_SDK --output="$WORK/dex/classes.dex" "$WORK/app.jar"

echo "== упаковка и подпись"
cp "$WORK/resources.apk" "$WORK/unsigned.apk"
(cd "$WORK/dex" && zip -q "$WORK/unsigned.apk" classes.dex)
zipalign -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
KS="${TENIR_KEYSTORE:-$CACHE/debug.keystore}"
if [ ! -s "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TENIR TV Debug,O=TENIR,C=KZ" >/dev/null 2>&1
fi
mkdir -p "$ROOT/dist"
APK="$ROOT/dist/tenir-tv-$VERSION_NAME.apk"
apksigner sign --ks "$KS" --ks-pass "pass:${TENIR_KEYSTORE_PASS:-android}" --ks-key-alias "${TENIR_KEY_ALIAS:-androiddebugkey}" \
  --min-sdk-version $MIN_SDK --out "$APK" "$WORK/aligned.apk"
rm -f "$APK.idsig"
apksigner verify "$APK"
echo "== готово: $APK ($(du -h "$APK" | cut -f1))"
