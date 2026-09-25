#!/system/bin/sh
# find-ubuntu.sh: print pid of ubuntu chroot anchor.
# REQUIRE: cmdline contains 'bash --login' (ubuntu init) — never match self/adb/su.
# /proc/PID/root view must show node AND server.js (ubuntu chroot markers).
SELF=$$
for p in $(ls /proc 2>/dev/null); do
  case "$p" in ''|*[!0-9]*) continue;; esac
  [ "$p" = "$SELF" ] && continue
  [ "$p" = "$PPID" ] && continue
  R=/proc/$p/root
  [ -x "$R/usr/bin/node" ] 2>/dev/null || continue
  [ -f "$R/sdcard/projects/Aegis/backend/server.js" ] 2>/dev/null || continue
  if tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q "bash --login"; then echo "$p"; break; fi
done
