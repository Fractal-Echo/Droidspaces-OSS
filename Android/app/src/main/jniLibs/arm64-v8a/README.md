# jniLibs/arm64-v8a

Prebuilt shared libraries required by the Wayland compositor.

## Required files

- `libwayland-server.so`
- `libffi.so`

## How to build

From the repo root, run:

    make wayland-libs

This runs the trierarch build script and copies the output here.

Prerequisites (host):
    meson  ninja  wayland  wayland-protocols  Android NDK

The NDK is found automatically from $ANDROID_NDK_HOME or
$HOME/Android/Sdk/ndk/. See:
    third_party/trierarch/trierarch-wayland/scripts/build-wayland-android.sh

## Updating

Re-run `make wayland-libs` whenever the wayland-server version needs
to change, then commit the new .so files with the wayland version in
the commit message.
