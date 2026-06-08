/*
 * Droidspaces v6 - Wayland compositor socket bridge
 *
 * The Wayland compositor (trierarch's libwayland-compositor.so) runs inside the
 * Droidspaces Android app process — NOT as a separate subprocess.  There is no
 * daemon to fork/exec here.  This file's sole responsibility is to bridge the
 * AF_UNIX socket that the app-side compositor creates into the container's
 * /run/user/0, and to inject the correct WAYLAND_DISPLAY / XDG_RUNTIME_DIR
 * environment variables so that every process spawned inside the container
 * finds the compositor automatically.
 *
 * Host side (app creates):
 *   /data/data/com.droidspaces.app/files/usr/tmp/wayland-1
 *
 * Container side (visible as):
 *   /run/user/0/wayland-1      (WAYLAND_DISPLAY=wayland-1, XDG_RUNTIME_DIR=/run/user/0)
 *
 * This follows the exact same bind-mount-socket pattern used by ds_setup_x11_socket()
 * in x11.c — no new mechanisms needed.
 *
 * Why no daemon start/stop?
 *   Unlike Termux-X11 (which needs app_process + SELinux dyntransition + OOM protection),
 *   the compositor is a shared library loaded by the Android app.  Android's process
 *   lifecycle manages it.  The droidspaces binary only needs to know the socket path.
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#define _GNU_SOURCE
#include "droidspace.h"
#include <sys/stat.h>

/* ---- public API ---------------------------------------------------------- */

/*
 * ds_setup_wayland_socket - bridge the compositor socket into the container.
 *
 * Called from setup_hardware_access() (hardware.c), after pivot_root, inside
 * the container mount namespace.  At this point /.old_root is still visible and
 * contains the full host filesystem.
 *
 * Returns 0 on success, -1 if the compositor socket doesn't exist yet (app has
 * not started the compositor — non-fatal, container still boots fine without it).
 */
int ds_setup_wayland_socket(struct ds_config *cfg) {
  if (!is_android())
    return 0;

  if (!cfg->wayland)
    return 0;

  /* Verify the socket exists on the host side.  The app creates it when
   * nativeSurfaceCreated / nativeStartServer is called.  If the user enabled
   * --wayland but the app hasn't started the compositor yet, warn and skip —
   * the container still works, just without Wayland. */
  if (access(DS_WL_HOST_SOCKET_OLDROOT, F_OK) != 0) {
    ds_warn("[Wayland] compositor socket not found at %s",
            DS_WL_HOST_SOCKET_OLDROOT);
    ds_warn("[Wayland] Is the Wayland compositor running in the Droidspaces app?");
    return -1;
  }

  /* Create the container-side runtime dir with the correct permissions.
   * /run/user/0 is the standard XDG_RUNTIME_DIR for root inside a container. */
  if (mkdir_p(DS_WL_CONTAINER_RUNTIME, 0700) < 0 && errno != EEXIST) {
    ds_warn("[Wayland] failed to create %s: %s", DS_WL_CONTAINER_RUNTIME,
            strerror(errno));
    return -1;
  }
  chmod(DS_WL_CONTAINER_RUNTIME, 0700);

  /* Bind-mount the socket file into the container.  ds_bind_mount_socket()
   * creates the destination file node, sets ownership + 0666, then bind-mounts. */
  if (ds_bind_mount_socket(DS_WL_HOST_SOCKET_OLDROOT, DS_WL_CONTAINER_SOCKET,
                           0 /* root owns it */, "Wayland") < 0) {
    return -1;
  }

  /* Inject WAYLAND_DISPLAY and XDG_RUNTIME_DIR so every container process
   * (including init, login shells, and display managers) sees the compositor
   * without any manual configuration. */
  setenv("WAYLAND_DISPLAY", DS_WL_SOCKET_NAME, 1);
  setenv("XDG_RUNTIME_DIR", DS_WL_CONTAINER_RUNTIME, 1);

  ds_log("[Wayland] socket bridged: %s -> %s", DS_WL_HOST_SOCKET_OLDROOT,
         DS_WL_CONTAINER_SOCKET);
  ds_log("Wayland: display is %s (XDG_RUNTIME_DIR=%s)", DS_WL_SOCKET_NAME,
         DS_WL_CONTAINER_RUNTIME);

  return 0;
}
