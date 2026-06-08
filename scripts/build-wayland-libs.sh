#!/bin/bash
# Build libwayland-server.so and libffi.so for the Droidspaces Wayland compositor.
#
# Output: Android/app/src/main/jniLibs/arm64-v8a/
#
# Prerequisites (host):
#   meson  ninja  wayland  wayland-protocols
#   Arch:  pacman -S meson ninja wayland wayland-protocols
#   Ubuntu: apt install meson ninja-build libwayland-dev wayland-protocols
#
# Android NDK: set ANDROID_NDK_HOME, or place under $HOME/Android/Sdk/ndk/
# wayland-scanner: from PATH, or set WAYLAND_SCANNER
#
# Usage:
#   ./scripts/build-wayland-libs.sh
#   make wayland-libs          (from repo root — calls this script)

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRIERARCH_WL="$REPO_ROOT/third_party/trierarch/trierarch-wayland"
JNILIBS="$REPO_ROOT/Android/app/src/main/jniLibs/arm64-v8a"

# ---------------------------------------------------------------------------
# NDK detection
# ---------------------------------------------------------------------------
NDK="${ANDROID_NDK_HOME:-${NDK:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    NDK_BASE="$HOME/Android/Sdk/ndk"
    if [ -d "$NDK_BASE" ]; then
        NDK=$(find "$NDK_BASE" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)
    fi
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || {
    echo "[!] Android NDK not found."
    echo "    Set ANDROID_NDK_HOME or install NDK under \$HOME/Android/Sdk/ndk/"
    exit 1
}

UNAME_S=$(uname -s | tr '[:upper:]' '[:lower:]')
UNAME_M=$(uname -m)
case "$UNAME_M" in
  x86_64|amd64)   NDK_HOST="${UNAME_S}-x86_64" ;;
  aarch64|arm64)  NDK_HOST="${UNAME_S}-arm64"   ;;
  *)               NDK_HOST="${UNAME_S}-x86_64" ;;
esac
[ -d "$NDK/toolchains/llvm/prebuilt/$NDK_HOST" ] || {
    echo "[!] NDK prebuilt not found: $NDK/toolchains/llvm/prebuilt/$NDK_HOST"
    exit 1
}
echo "[*] NDK: $NDK ($NDK_HOST)"

# ---------------------------------------------------------------------------
# wayland-scanner detection
# ---------------------------------------------------------------------------
WAYLAND_SCANNER="${WAYLAND_SCANNER:-$(command -v wayland-scanner 2>/dev/null || true)}"
[ -z "$WAYLAND_SCANNER" ] && [ -x /usr/bin/wayland-scanner ] && WAYLAND_SCANNER=/usr/bin/wayland-scanner
[ -n "$WAYLAND_SCANNER" ] && [ -x "$WAYLAND_SCANNER" ] || {
    echo "[!] wayland-scanner not found."
    echo "    Arch:  pacman -S wayland"
    echo "    Ubuntu: apt install libwayland-dev"
    exit 1
}

# ---------------------------------------------------------------------------
# Working directories (inside the submodule, not tracked by Droidspaces git)
# ---------------------------------------------------------------------------
BUILD_DIR="$TRIERARCH_WL/build-src"
FFI_INSTALL="$TRIERARCH_WL/libffi-install"
WAYLAND_SRC="${WAYLAND_SRC:-$BUILD_DIR/wayland}"

CROSS_FILE="/tmp/ds-cross-android-$$.txt"
trap "rm -f $CROSS_FILE" EXIT
sed -e "s|@NDK@|$NDK|g" -e "s|@NDK_HOST@|$NDK_HOST|g" \
    "$TRIERARCH_WL/scripts/cross-android-arm64.txt" > "$CROSS_FILE"

# ---------------------------------------------------------------------------
# 1. libffi
# ---------------------------------------------------------------------------
FFI_VERSION="3.4.6"
FFI_SRC="$TRIERARCH_WL/libffi"
FFI_TAR="$TRIERARCH_WL/libffi-${FFI_VERSION}.tar.gz"

if [ -d "$FFI_SRC" ] && { [ ! -f "$FFI_SRC/.ffi-version" ] || \
    [ "$(cat "$FFI_SRC/.ffi-version" 2>/dev/null)" != "$FFI_VERSION" ]; }; then
    echo "[*] Upgrading libffi, removing old..."
    rm -rf "$FFI_SRC"
fi
if [ ! -f "$FFI_TAR" ]; then
    echo "[*] Downloading libffi $FFI_VERSION..."
    curl -sL "https://github.com/libffi/libffi/releases/download/v${FFI_VERSION}/libffi-${FFI_VERSION}.tar.gz" \
        -o "$FFI_TAR"
fi
if [ ! -d "$FFI_SRC" ] || [ ! -f "$FFI_SRC/configure" ]; then
    echo "[*] Extracting libffi..."
    rm -rf "$FFI_SRC"
    tar xzf "$FFI_TAR" -C "$TRIERARCH_WL"
    mv "$TRIERARCH_WL/libffi-${FFI_VERSION}" "$FFI_SRC"
    echo "$FFI_VERSION" > "$FFI_SRC/.ffi-version"
fi

echo "[*] Building libffi $FFI_VERSION for Android arm64..."
rm -rf "$FFI_INSTALL" "$FFI_SRC/build-android"
mkdir -p "$FFI_INSTALL" "$FFI_SRC/build-android"
(cd "$FFI_SRC/build-android" && \
    CC="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin/aarch64-linux-android23-clang" \
    CFLAGS="-DANDROID -fPIC -std=gnu11" \
    LDFLAGS="-fPIC" \
    "$FFI_SRC/configure" --host=aarch64-linux-android --prefix="$FFI_INSTALL" \
        --disable-docs --disable-multi-os-directory --disable-static \
        >/dev/null 2>&1)
make -C "$FFI_SRC/build-android" -j"$(nproc)" >/dev/null 2>&1
make -C "$FFI_SRC/build-android" install >/dev/null 2>&1
echo "[+] libffi built"

export PKG_CONFIG_PATH="$FFI_INSTALL/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"

# ---------------------------------------------------------------------------
# 2. wayland-server
# ---------------------------------------------------------------------------
mkdir -p "$BUILD_DIR"
if [ -d "$WAYLAND_SRC" ]; then
    echo "[*] Removing existing wayland source, re-cloning..."
    rm -rf "$WAYLAND_SRC"
fi

HOST_VER="$(pkg-config --modversion wayland-scanner 2>/dev/null || true)"
WAYLAND_VERSION="${WAYLAND_VERSION:-${HOST_VER:-1.25.0}}"
echo "[*] Cloning wayland $WAYLAND_VERSION..."
git clone --quiet --depth 1 --branch "$WAYLAND_VERSION" \
    https://gitlab.freedesktop.org/wayland/wayland.git "$WAYLAND_SRC"
rm -rf "$WAYLAND_SRC/.git"

WAYLAND_OUT="$TRIERARCH_WL/libs"
echo "[*] Building libwayland-server (wayland $WAYLAND_VERSION)..."
rm -rf "$WAYLAND_SRC/build-android"
mkdir -p "$WAYLAND_SRC/build-android"
meson setup "$WAYLAND_SRC/build-android" "$WAYLAND_SRC" \
    --cross-file "$CROSS_FILE" \
    -Dlibraries=true \
    -Dscanner=false \
    -Dtests=false \
    -Ddocumentation=false \
    -Ddtd_validation=false \
    --prefix "$WAYLAND_OUT" \
    --libdir lib \
    >/dev/null 2>&1
meson compile -C "$WAYLAND_SRC/build-android" >/dev/null 2>&1
meson install -C "$WAYLAND_SRC/build-android" >/dev/null 2>&1
echo "[+] libwayland-server built"

# ---------------------------------------------------------------------------
# 3. Copy to jniLibs
# ---------------------------------------------------------------------------
mkdir -p "$JNILIBS"

# libwayland-server.so — meson installs a versioned .so + unversioned symlink;
# we only need the unversioned one (linker resolves it at load time on Android).
cp -L "$WAYLAND_OUT/lib/libwayland-server.so" "$JNILIBS/libwayland-server.so"

# libffi.so — autotools installs libffi.so.X.Y.Z + symlinks; copy the
# unversioned symlink as a regular file so the APK packages it correctly.
cp -L "$FFI_INSTALL/lib/libffi.so" "$JNILIBS/libffi.so"

echo ""
echo "[+] Done. Installed to $JNILIBS:"
ls -lh "$JNILIBS"/*.so
echo ""
echo "[!] Commit the .so files with the wayland version in the message:"
echo "    git add Android/app/src/main/jniLibs/arm64-v8a/"
echo "    git commit -m \"chore(jniLibs): update wayland prebuilts to $WAYLAND_VERSION\""
