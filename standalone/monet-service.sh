#!/system/bin/sh
# Theme-only root supervisor. Launcher identity is cached by PID + starttime.
BASE=/data/local/tmp/watchworkshop-runtime
SELF=$BASE/monet-service.sh
PKG=com.heytap.wearable.launcher
PIDFILE=$BASE/service.pid
STARTFILE=$BASE/service.start
LOCK=$BASE/service.lock
MARK=/data/user_de/0/$PKG/files/watch-monet-bridge.json
INJECT=$BASE/frida-inject
PROC_START=
read_start() {
  local line tail
  [ -r "/proc/$1/stat" ] || return 1
  IFS= read -r line <"/proc/$1/stat" || return 1
  tail=${line##*) }
  set -- $tail
  [ $# -ge 20 ] || return 1
  shift 19
  PROC_START=$1
}
cmdline() { tr '\0' ' ' <"/proc/$1/cmdline" 2>/dev/null; }
is_launcher() {
  set -- $(cmdline "$1")
  [ "$#" = 1 ] && [ "$1" = "$PKG" ]
}
launcher() {
  set -- $(pidof "$PKG" 2>/dev/null)
  TARGET=${1:-}
  case "$TARGET" in ''|*[!0-9]*) return 1;; esac
  is_launcher "$TARGET" && read_start "$TARGET"
}
service_pid() {
  local p s
  [ -r "$PIDFILE" ] || return 1
  IFS= read -r p <"$PIDFILE"
  case "$p" in ''|*[!0-9]*) return 1;; esac
  [ "$(cmdline "$p")" = "sh $SELF run " ] || return 1
  IFS= read -r s <"$STARTFILE" || return 1
  read_start "$p" && [ "$s" = "$PROC_START" ] || return 1
  printf '%s\n' "$p"
}
ours() { [ -r "$MARK" ] && grep -q '"pid":'"$1"',"error":""' "$MARK"; }
mapped() { grep -q frida-agent-32.so "/proc/$1/maps" 2>/dev/null; }
log() {
  # Rotation happens only on events, no steady-state disk writes.
  [ ! -f "$BASE/service.log" ] || [ "$(wc -c <"$BASE/service.log")" -lt 65536 ] || mv "$BASE/service.log" "$BASE/service.log.old"
  printf '%s %s\n' "$(date '+%m-%d %H:%M:%S')" "$*" >>"$BASE/service.log"
}
inject() {
  local p child n rc stat tail
  p=$1
  if mapped "$p"; then
    ours "$p" && return 0
    log "foreign Frida agent pid=$p; refusing duplicate injection"
    return 1
  fi
  log "inject pid=$p"
  "$INJECT" -p "$p" -s "$BASE/hook.js" -e >"$BASE/inject.log" 2>&1 &
  child=$!
  printf '%s\n' "$child" >"$BASE/inject.pid"
  n=0
  while kill -0 "$child" 2>/dev/null; do
    # A completed child may remain a zombie until wait; kill -0 alone is insufficient.
    if IFS= read -r stat <"/proc/$child/stat" 2>/dev/null; then
      tail=${stat##*) }
      case "$tail" in Z\ *|X\ *|x\ *) break;; esac
    else break; fi
    n=$((n+1)); [ "$n" -lt 60 ] || { kill "$child" 2>/dev/null; break; }
    sleep 1
  done
  wait "$child"; rc=$?
  rm -f "$BASE/inject.pid"
  log "inject rc=$rc"
  [ "$rc" = 0 ] || return 1
  n=0
  while [ "$n" -lt 12 ]; do
    ours "$p" && return 0
    is_launcher "$p" || return 1
    n=$((n+1)); sleep 1
  done
  log "bridge did not initialize pid=$p"
  return 1
}
run() {
  local owner active armed stamp counter
  [ ! -f /data/local/tmp/watchhide/hook.js ] && [ ! -f /data/local/tmp/watch-monet/hook.js ] || { echo 'Existing theme runtime detected'; return 1; }
  if ! mkdir "$LOCK" 2>/dev/null; then
    active=$(service_pid) && return 0
    rmdir "$LOCK" 2>/dev/null
    mkdir "$LOCK" 2>/dev/null || return 1
  fi
  printf '%s\n' "$$" >"$PIDFILE"
  read_start "$$" || return 1
  printf '%s\n' "$PROC_START" >"$STARTFILE"
  trap 'owner=""; IFS= read -r owner <"$PIDFILE"; if [ "$owner" = "$$" ]; then rm -f "$PIDFILE" "$STARTFILE"; rmdir "$LOCK"; fi; exit 0' TERM INT
  trap '' HUP
  log "service start pid=$$"
  armed=; stamp=; counter=0
  while :; do
    [ -f "$BASE/installed" ] && [ ! -f "$BASE/disabled" ] || { stop; return 0; }
    IFS= read -r owner <"$PIDFILE" || return 1
    [ "$owner" = "$$" ] || return 0
    if [ -n "$armed" ] && read_start "$armed" && [ "$PROC_START" = "$stamp" ]; then
      counter=$((counter+1))
      if [ "$counter" -lt 12 ]; then sleep 5; continue; fi
      counter=0
      if ours "$armed" && mapped "$armed"; then sleep 5; continue; fi
      armed=
    fi
    if launcher; then
      armed=$TARGET; stamp=$PROC_START; counter=0
      sleep 2
      if is_launcher "$armed" && read_start "$armed" && [ "$PROC_START" = "$stamp" ] && inject "$armed"; then :;
      else armed=; sleep 20; fi
    fi
    sleep 5
  done
}
status() {
  local s
  s=$(service_pid) || s=
  printf 'service_pid=%s\n' "$s"
  if launcher; then
    printf 'launcher_pid=%s\n' "$TARGET"
    if ours "$TARGET" && mapped "$TARGET"; then echo 'theme_live=yes'; else echo 'theme_live=no'; fi
  else echo 'launcher_pid='; echo 'theme_live=no'; fi
  [ ! -r "$MARK" ] || cat "$MARK"
  printf '\n'
}
stop() {
  local p child
  p=$(service_pid) && kill "$p" 2>/dev/null
  if [ -r "$BASE/inject.pid" ]; then
    IFS= read -r child <"$BASE/inject.pid"
    case "$child" in ''|*[!0-9]*) :;; *) case "$(cmdline "$child")" in "$INJECT -p "*) kill "$child" 2>/dev/null;; esac;; esac
  fi
  sleep 1
  # Only fixed files under this package's fixed BASE are removed.
  rm -f "$PIDFILE" "$STARTFILE" "$BASE/inject.pid"
  rmdir "$LOCK" 2>/dev/null
  return 0
}
[ "$(id -u)" = 0 ] || { echo 'Root adbd required'; exit 1; }
case "${1:-status}" in
  ensure)
    [ -f "$BASE/installed" ] || { echo 'Module not installed'; exit 1; }
    [ ! -f /data/local/tmp/watchhide/hook.js ] && [ ! -f /data/local/tmp/watch-monet/hook.js ] || { echo 'Existing theme runtime detected'; exit 1; }
    [ -e "$BASE/disabled" ] && { echo 'service disabled'; exit 1; }
    [ -x "$INJECT" ] && [ -r "$BASE/hook.js" ] && [ -r "$BASE/theme.jar" ] || { echo 'Runtime files missing; reinstall the package'; exit 1; }
    service_pid >/dev/null && { echo 'service already running'; exit 0; }
    setsid sh "$SELF" run </dev/null >/dev/null 2>&1 &
    n=0
    while [ "$n" -lt 5 ]; do
      sleep 1
      service_pid >/dev/null && { echo 'service started'; exit 0; }
      n=$((n+1))
    done
    echo 'Service startup failed; inspect service.log'; exit 1;;
  run) run;;
  status) status;;
  stop) stop;;
  restart-launcher)
    if launcher; then kill "$TARGET"; echo 'launcher restarting'; else echo 'launcher not running; no resident theme to clear'; fi;;
  *) echo 'Usage: ensure|status|stop|restart-launcher'; exit 2;;
esac
