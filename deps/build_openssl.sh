#!/bin/bash
# 交叉编译 OpenSSL 3.3.2（android-arm API 24 静态库），输出到 deps/openssl-build/armeabi-v7a
# 仓库已附带预编译产物，仅在需要重编时运行本脚本。
# NDK 路径：优先读环境变量 NDK_HOME / ANDROID_NDK_HOME，否则探测常见安装位置。
set -e

VER=3.3.2
API=24
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/openssl-build/armeabi-v7a"

# --- NDK 探测 ---
if [ -z "$NDK_HOME" ] && [ -n "$ANDROID_NDK_HOME" ]; then NDK_HOME="$ANDROID_NDK_HOME"; fi
if [ -z "$NDK_HOME" ]; then
  for d in "$HOME/Library/Android/sdk/ndk"/* "$HOME/Android/Sdk/ndk"/* "$HOME/Android/Sdk/ndk-bundle"; do
    [ -d "$d" ] && NDK_HOME="$d" && break
  done
fi
[ -z "$NDK_HOME" ] && { echo "错误: 未找到 Android NDK，请设置 NDK_HOME=<ndk路径>"; exit 1; }
echo "NDK: $NDK_HOME"

# --- OpenSSL 源码（缺失则下载） ---
if [ ! -f "$HERE/openssl/Configure" ]; then
  echo "下载 openssl-$VER 源码..."
  mkdir -p "$HERE/openssl"
  curl -L "https://github.com/openssl/openssl/releases/download/openssl-$VER/openssl-$VER.tar.gz" \
    | tar xz --strip-components=1 -C "$HERE/openssl"
fi

export ANDROID_NDK_ROOT="$NDK_HOME"
export PATH="$NDK_HOME/toolchains/llvm/prebuilt/*/bin:$PATH"

cd "$HERE/openssl"
make clean >/dev/null 2>&1 || true
./Configure android-arm -D__ANDROID_API__=$API \
    no-shared no-tests no-docs no-legacy no-comp no-asm \
    --prefix="$OUT"
make build_generated
make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)" libcrypto.a libssl.a
mkdir -p "$OUT/lib" "$OUT/include"
cp libcrypto.a libssl.a "$OUT/lib/"
cp -r include/* "$OUT/include/"
echo "=== OpenSSL 构建完成: $OUT ==="
ls -la "$OUT/lib"
