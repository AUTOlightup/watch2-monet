#!/system/bin/sh
# Fixed Monet lifecycle. Run from the app's private, APK-supplied payload directory.
BASE=/data/local/tmp/watchworkshop-runtime
SOURCE=/data/user/0/dev.codex.watchworkshop/files/monet
PKG=com.heytap.wearable.launcher
SHARED=/data/local/tmp/watchhide/hook.js
LEGACY=/data/local/tmp/watch-monet/hook.js
THEME_SHA=706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74
HOOK_SHA=473ab51ed1888c278a414877eaafe04b59c6aa6d02a670548a50982ecac6aa35
SHARED_SHA=6e46e44d3b4f5fc2162b60dd90b5de2b3c2e4f4cfcf50d87eee5c5071883f722
FRIDA_SHA=e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea
fail() { echo "$1"; exit 1; }
file_sha() { sha256sum "$1" 2>/dev/null | cut -d ' ' -f 1; }
own() { [ -f "$BASE/installed" ] && [ "$(file_sha "$BASE/theme.jar")" = "$THEME_SHA" ] && [ "$(file_sha "$BASE/hook.js")" = "$HOOK_SHA" ]; }
base_ready() { [ -f "$BASE/manager-ready" ] && [ "$(file_sha "$BASE/frida-inject")" = "$FRIDA_SHA" ]; }
compatible() {
  [ "$(getprop ro.product.model)" = OW20W1 ] && [ "$(getprop ro.build.display.id)" = OW20W1_11_A.92 ] && [ "$(getprop ro.build.version.sdk)" = 27 ] && [ "$(getprop ro.product.cpu.abi)" = armeabi-v7a ] || fail DEVICE_UNSUPPORTED
}
restart_launcher() {
  local p actual
  p=$(pidof "$PKG")
  case "$p" in ''|*[!0-9]*) fail LAUNCHER_UNAVAILABLE;; esac
  actual=$(tr '\000' ' ' <"/proc/$p/cmdline")
  # Android pads argv[0] with multiple NUL bytes. Ignore padding, keep the single-argument identity check.
  set -- $actual
  [ "$#" = 1 ] && [ "$1" = "$PKG" ] || fail LAUNCHER_IDENTITY
  kill -9 "$p" || fail LAUNCHER_RESTART_FAILED
}
stop_module() {
  touch "$BASE/disabled" || fail WRITE_FAILED
  sh "$BASE/monet-service.sh" stop || fail STOP_FAILED
  restart_launcher
}
status() {
  if [ -f "$SHARED" ]; then
    if [ "$(file_sha "$SHARED")" = "$SHARED_SHA" ] && [ "$(file_sha /data/local/tmp/watchface-theme/theme.jar)" = "$THEME_SHA" ]; then echo shared; else echo conflict; fi
  elif [ -f "$LEGACY" ]; then echo conflict
  elif [ -f "$BASE/installed" ]; then
    if own; then [ ! -f "$BASE/disabled" ] && echo installed || echo disabled; else echo conflict; fi
  elif base_ready; then echo available
  else echo setup; fi
}
[ "$(id -u)" = 0 ] || fail ROOT_REQUIRED
compatible
ACTION=${1:-status}
[ "$ACTION" != status ] || { status; exit 0; }
[ ! -f "$SHARED" ] && [ ! -f "$LEGACY" ] || fail SHARED_RUNTIME
# A 1.0 standalone installation already has the manager's base dependencies.
if own && [ "$(file_sha "$BASE/frida-inject")" = "$FRIDA_SHA" ]; then touch "$BASE/manager-ready"; fi
base_ready || fail SETUP_REQUIRED
case "$ACTION" in
  install)
    if own; then [ ! -f "$BASE/disabled" ] || fail MODULE_DISABLED; exit 0; fi
    [ ! -f "$BASE/installed" ] && [ ! -f "$BASE/theme.jar" ] && [ ! -f "$BASE/hook.js" ] && [ ! -f "$BASE/monet-service.sh" ] || fail UNKNOWN_FILES
    p=$(pidof "$PKG")
    case "$p" in ''|*[!0-9]*) fail LAUNCHER_UNAVAILABLE;; esac
    ! grep -q frida-agent-32.so "/proc/$p/maps" 2>/dev/null || fail FOREIGN_RUNTIME
    [ "$(file_sha "$SOURCE/theme.jar")" = "$THEME_SHA" ] && [ "$(file_sha "$SOURCE/hook.js")" = "$HOOK_SHA" ] || fail PAYLOAD_INVALID
    trap 'rm -f "$BASE/theme.jar.new" "$BASE/hook.js.new" "$BASE/monet-service.sh.new" "$BASE/theme.jar" "$BASE/hook.js" "$BASE/monet-service.sh" "$BASE/installed" "$BASE/disabled"' 0
    # Stage all files before publishing the installed marker. A failed copy is retryable.
    for file in theme.jar hook.js monet-service.sh; do
      cp "$SOURCE/$file" "$BASE/$file.new" || fail COPY_FAILED
    done
    chmod 644 "$BASE/theme.jar.new" "$BASE/hook.js.new" || fail WRITE_FAILED
    chmod 755 "$BASE/monet-service.sh.new" || fail WRITE_FAILED
    for file in theme.jar hook.js monet-service.sh; do mv "$BASE/$file.new" "$BASE/$file" || fail WRITE_FAILED; done
    touch "$BASE/disabled" "$BASE/installed" || fail WRITE_FAILED
    trap - 0
    echo INSTALLED_DISABLED;;
  enable)
    own || fail MODULE_NOT_INSTALLED
    rm -f "$BASE/disabled"
    sh "$BASE/monet-service.sh" ensure || { touch "$BASE/disabled"; fail START_FAILED; }
    echo ENABLED;;
  disable)
    own || fail MODULE_NOT_INSTALLED
    stop_module
    echo DISABLED;;
  uninstall)
    own || fail MODULE_NOT_INSTALLED
    stop_module
    # Keep the manager, Frida, authorization, recovery service and saved base settings.
    rm -f "$BASE/installed" "$BASE/theme.jar" "$BASE/hook.js" "$BASE/monet-service.sh" "$BASE/disabled"
    [ ! -f "$BASE/installed" ] && [ ! -f "$BASE/theme.jar" ] && [ ! -f "$BASE/hook.js" ] && [ ! -f "$BASE/monet-service.sh" ] || fail REMOVE_FAILED
    echo UNINSTALLED;;
  *) fail INVALID_ACTION;;
esac
