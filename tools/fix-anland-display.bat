@echo off
setlocal enabledelayedexpansion

set "DROIDSPACES=/data/local/Droidspaces/bin/droidspaces"
set "CONTAINER=ubuntu"
set "CONFIG=/data/local/Droidspaces/Containers/%CONTAINER%/container.config"
set "ENVFILE=/data/local/Droidspaces/Containers/%CONTAINER%/anland.env"
set "ANLAND_SOCKET_HOST=/data/local/tmp/display_daemon.sock"
set "TMP_SH=/data/local/tmp/fix-anland-display.sh"

where adb >nul 2>&1
if errorlevel 1 (
  echo [ERRO] adb nao encontrado no PATH.
  exit /b 1
)

adb get-state >nul 2>&1
if errorlevel 1 (
  echo [ERRO] Nenhum smartphone ADB autorizado.
  adb devices
  exit /b 1
)

echo [1/5] Verificando root e Anland...
adb shell "su -c 'id >/dev/null && test -x %DROIDSPACES% && test -S %ANLAND_SOCKET_HOST%'"
if errorlevel 1 (
  echo [ERRO] Root, Droidspaces ou socket do Anland nao esta pronto.
  echo        Abra o app Anland e confirme que /data/local/tmp/display_daemon.sock existe.
  exit /b 1
)

echo [2/5] Enviando script de reparo...
set "LOCAL_SH=%~dp0fix-anland-display.sh"
if not exist "%LOCAL_SH%" (
  echo [ERRO] Nao encontrei "%LOCAL_SH%".
  exit /b 1
)

adb push "%LOCAL_SH%" "%TMP_SH%" >nul
if errorlevel 1 (
  echo [ERRO] Falha ao enviar script.
  exit /b 1
)

echo [3/5] Aplicando correcao Anland...
adb shell "su -c 'chmod 755 %TMP_SH% && %TMP_SH%'"
if errorlevel 1 (
  echo [ERRO] Falha ao aplicar correcao.
  exit /b 1
)

echo [4/5] Resultado:
adb shell "su -c '%DROIDSPACES% --name=%CONTAINER% run printenv ANLAND ANLAND_SOCKET WAYLAND_DISPLAY PULSE_SERVER'"

echo [5/5] Concluido. Abra/leve o app Anland para frente se a tela ainda nao estiver visivel.
endlocal
