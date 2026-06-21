#!/system/bin/sh
set -eu

DROIDSPACES="${DROIDSPACES:-/data/local/Droidspaces/bin/droidspaces}"
CONTAINER="${CONTAINER:-ubuntu}"
CONFIG="${CONFIG:-/data/local/Droidspaces/Containers/$CONTAINER/container.config}"
ENVFILE="${ENVFILE:-/data/local/Droidspaces/Containers/$CONTAINER/anland.env}"
ANLAND_SOCKET_HOST="${ANLAND_SOCKET_HOST:-/data/local/tmp/display_daemon.sock}"

die() {
  echo "[ERRO] $*" >&2
  exit 1
}

[ "$(id -u)" = "0" ] || die "execute como root via su"
[ -x "$DROIDSPACES" ] || die "Droidspaces nao encontrado: $DROIDSPACES"
[ -f "$CONFIG" ] || die "Config nao encontrada: $CONFIG"
[ -S "$ANLAND_SOCKET_HOST" ] || die "Socket Anland nao encontrado: $ANLAND_SOCKET_HOST"

echo "[1/6] Parando container $CONTAINER..."
"$DROIDSPACES" --name="$CONTAINER" stop >/dev/null 2>&1 || true

echo "[2/6] Criando env_file Anland..."
cat > "$ENVFILE" <<'EOF'
ANLAND=1
ANLAND_SOCKET=/run/display.sock
ANLAND_DRM_DEVICE=/dev/dri/renderD128
WAYLAND_DISPLAY=wayland-0
XDG_SESSION_TYPE=wayland
QT_QPA_PLATFORM=wayland
MESA_LOADER_DRIVER_OVERRIDE=kgsl
GALLIUM_DRIVER=kgsl
FD_FORCE_KGSL=1
TU_DEBUG=noconform
XCURSOR_SIZE=48
PULSE_SERVER=unix:/tmp/.pulse-socket
EOF
chmod 0644 "$ENVFILE"

echo "[3/6] Ajustando container.config para Anland..."
tmp_config=/data/local/tmp/container.config.anland
awk '
  /^enable_termux_x11=/ {print "enable_termux_x11=0"; next}
  /^enable_hw_access=/ {print "enable_hw_access=1"; next}
  /^enable_gpu_mode=/ {print "enable_gpu_mode=1"; next}
  /^env_file=/ {next}
  /^bind_mounts=/ {next}
  {print}
  END {
    print "env_file=/data/local/Droidspaces/Containers/ubuntu/anland.env";
    print "bind_mounts=/data/local/tmp/display_daemon.sock:/run/display.sock";
  }
' "$CONFIG" > "$tmp_config"
cp "$tmp_config" "$CONFIG"
chmod 0666 "$CONFIG"

echo "[4/6] Iniciando container com socket Anland..."
"$DROIDSPACES" --config="$CONFIG" start

echo "[5/6] Corrigindo /etc/environment dentro do container..."
"$DROIDSPACES" --name="$CONTAINER" run sh -lc 'cat > /etc/environment <<EOF
XCURSOR_SIZE=48
WAYLAND_DISPLAY=wayland-0
PULSE_SERVER=unix:/tmp/.pulse-socket
ANLAND=1
ANLAND_SOCKET=/run/display.sock
ANLAND_DRM_DEVICE=/dev/dri/renderD128
MESA_LOADER_DRIVER_OVERRIDE=kgsl
GALLIUM_DRIVER=kgsl
FD_FORCE_KGSL=1
QT_QPA_PLATFORM=wayland
XDG_SESSION_TYPE=wayland
TU_DEBUG=noconform
EOF'

echo "[6/6] Reiniciando e subindo KDE/Anland..."
"$DROIDSPACES" --name="$CONTAINER" restart
"$DROIDSPACES" --name="$CONTAINER" run sh -lc \
  'nohup /usr/local/bin/startanland-kde.sh >/tmp/anland-kde.log 2>&1 &'

echo
echo "[OK] Estado atual:"
"$DROIDSPACES" --name="$CONTAINER" run sh -lc \
  'printenv ANLAND ANLAND_SOCKET WAYLAND_DISPLAY PULSE_SERVER;
   ls -l /run/display.sock /dev/dri/renderD128 /run/user/0/wayland-0 2>/dev/null || true;
   ps -ef | grep -E "kwin_wayland|plasmashell" | grep -v grep || true;
   tail -40 /tmp/anland-kde.log 2>/dev/null || true'
