#!/usr/bin/env bash
# 打包原生 Android App（mobile/Android）为 release 签名的 APK：universal + 各 ABI 单独包。
# 本机与 CI（.github/workflows/android-package.yml）共用这一份流程。
#
# 用法：mobile/Android/scripts/package.sh --version X.Y.Z[-后缀] [--signing FILE] [--abis LIST] [--out DIR]
#   --version  versionName（可带 -rc.1 / -manual.7 之类后缀）；versionCode 由前三段派生（见 app/build.gradle.kts）
#   --signing  签名属性文件：storeFile（相对该文件所在目录或绝对路径）/ storePassword / keyAlias / keyPassword。
#              缺省读环境变量 FLUXDOWN_ANDROID_KEYSTORE / FLUXDOWN_ANDROID_KEYSTORE_PASSWORD /
#              FLUXDOWN_ANDROID_KEY_ALIAS / FLUXDOWN_ANDROID_KEY_PASSWORD（CI 用）；两者都没有则失败
#   --abis     逗号分隔的 ABI，默认 arm64-v8a,x86_64（:bridge 为每个 ABI 交叉编译一份引擎库）
#   --out      产物目录，默认 mobile/Android/build/package
# 产物：FluxDown-<version>-android-native-{universal,<abi>}.apk + SHA256SUMS.txt
# 前置：JDK 17+（未设 JAVA_HOME 时回退 Android Studio 自带 JBR）、Android SDK（ANDROID_HOME 或 local.properties 的 sdk.dir）、
#       rustup target add <各 ABI 对应三元组>、cargo install cargo-ndk。
#       可选 FLUXCLOUD_BASE_URL（编译期写入核心；空串会被 option_env! 当成已设置，脚本会清掉）。
set -euo pipefail

VERSION=""
SIGNING=""
ABIS="arm64-v8a,x86_64"
OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:-}"; shift ;;
    --signing) SIGNING="${2:-}"; shift ;;
    --abis) ABIS="${2:-}"; shift ;;
    --out) OUT="${2:-}"; shift ;;
    *) echo "unknown argument: $1 (expected --version, --signing, --abis, --out)" >&2; exit 64 ;;
  esac
  shift
done
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] \
  || { echo "--version must be X.Y.Z[-suffix] (got '$VERSION')" >&2; exit 64; }
ABIS="$(tr -d '[:space:]' <<< "$ABIS")"
[[ "$ABIS" =~ ^[0-9a-z_-]+(,[0-9a-z_-]+)*$ ]] || { echo "--abis must be a comma-separated ABI list (got '$ABIS')" >&2; exit 64; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="${OUT:-$ANDROID_ROOT/build/package}"

prop() { # prop FILE KEY：读取 .properties 的一个值（不支持续行 / 转义，签名文件只有简单键值）
  sed -n "s/^[[:space:]]*$2[[:space:]]*=[[:space:]]*//p" "$1" | tail -n 1
}

if [ -n "$SIGNING" ]; then
  [ -f "$SIGNING" ] || { echo "signing file not found: $SIGNING" >&2; exit 64; }
  SIGNING_DIR="$(cd "$(dirname "$SIGNING")" && pwd)"
  STORE_FILE="$(prop "$SIGNING" storeFile)"
  case "$STORE_FILE" in
    /*) ;;
    ?*) STORE_FILE="$SIGNING_DIR/$STORE_FILE" ;;
  esac
  export FLUXDOWN_ANDROID_KEYSTORE="$STORE_FILE"
  FLUXDOWN_ANDROID_KEYSTORE_PASSWORD="$(prop "$SIGNING" storePassword)"
  FLUXDOWN_ANDROID_KEY_ALIAS="$(prop "$SIGNING" keyAlias)"
  FLUXDOWN_ANDROID_KEY_PASSWORD="$(prop "$SIGNING" keyPassword)"
  export FLUXDOWN_ANDROID_KEYSTORE_PASSWORD FLUXDOWN_ANDROID_KEY_ALIAS FLUXDOWN_ANDROID_KEY_PASSWORD
fi
for var in FLUXDOWN_ANDROID_KEYSTORE FLUXDOWN_ANDROID_KEYSTORE_PASSWORD FLUXDOWN_ANDROID_KEY_ALIAS FLUXDOWN_ANDROID_KEY_PASSWORD; do
  [ -n "${!var:-}" ] || { echo "missing $var (pass --signing FILE or export it)" >&2; exit 64; }
done
[ -f "$FLUXDOWN_ANDROID_KEYSTORE" ] || { echo "keystore not found: $FLUXDOWN_ANDROID_KEYSTORE" >&2; exit 64; }

if [ -z "${JAVA_HOME:-}" ]; then
  STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  [ -d "$STUDIO_JBR" ] && export JAVA_HOME="$STUDIO_JBR"
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$ANDROID_ROOT/local.properties" ]; then
  SDK="$(prop "$ANDROID_ROOT/local.properties" sdk.dir)"
fi
[ -d "$SDK/build-tools" ] || { echo "Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)" >&2; exit 64; }
BUILD_TOOLS="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
APKSIGNER="$BUILD_TOOLS/apksigner"
[ -x "$APKSIGNER" ] || { echo "apksigner not found in $BUILD_TOOLS" >&2; exit 64; }

if [ -n "${FLUXCLOUD_BASE_URL:-}" ]; then
  echo "==> FluxCloud: $FLUXCLOUD_BASE_URL"
else
  unset FLUXCLOUD_BASE_URL
  echo "==> FluxCloud: http://127.0.0.1:8720 (default; export FLUXCLOUD_BASE_URL for a real server)"
fi
# 引擎 UA 等编译期版本（native/engine/build.rs），与桌面发行物一致
export FLUXDOWN_APP_VERSION="$VERSION"

echo "==> gradle :app:assembleRelease $VERSION ($ABIS)"
(
  cd "$ANDROID_ROOT"
  ./gradlew --no-daemon \
    "-Pfluxdown.version=$VERSION" "-Pfluxdown.abis=$ABIS" -Pfluxdown.splitAbi=true \
    :app:assembleRelease
)

APK_DIR="$ANDROID_ROOT/app/build/outputs/apk/release"
rm -rf "$OUT"
mkdir -p "$OUT"
IFS=',' read -ra ABI_LIST <<< "$ABIS"
for flavor in universal "${ABI_LIST[@]}"; do
  SRC="$APK_DIR/app-$flavor-release.apk"
  [ -f "$SRC" ] || { echo "gradle produced no $SRC" >&2; exit 1; }
  DEST="$OUT/FluxDown-$VERSION-android-native-$flavor.apk"
  cp "$SRC" "$DEST"
  # 包内每个 ABI 都必须带引擎库（夹带 JNA 等依赖的其它 ABI 会装得上却加载即崩）；
  # universal 含全部所选 ABI，单 ABI 包只含自己。
  LIBS="$(unzip -Z1 "$DEST" 'lib/*' 2>/dev/null || true)"
  PACKED="$(cut -d/ -f2 <<< "$LIBS" | sort -u | paste -sd, -)"
  if [ "$flavor" = universal ]; then EXPECTED="$(tr ',' '\n' <<< "$ABIS" | sort -u | paste -sd, -)"; else EXPECTED="$flavor"; fi
  [ "$PACKED" = "$EXPECTED" ] || { echo "$DEST packs ABIs [$PACKED], expected [$EXPECTED]" >&2; exit 1; }
  for abi in ${PACKED//,/ }; do
    grep -qx "lib/$abi/libfluxdown_mobile.so" <<< "$LIBS" \
      || { echo "$DEST lacks lib/$abi/libfluxdown_mobile.so" >&2; exit 1; }
  done
  "$APKSIGNER" verify --min-sdk-version 31 "$DEST"
done

echo "==> signer"
"$APKSIGNER" verify --print-certs "$OUT/FluxDown-$VERSION-android-native-universal.apk" 2>/dev/null | grep -E 'DN|SHA-256'
(cd "$OUT" && shasum -a 256 ./*.apk | sed 's# \./# #' > SHA256SUMS.txt && cat SHA256SUMS.txt)
echo "==> packaged $VERSION into $OUT"
