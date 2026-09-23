#!/system/bin/sh
# Stage node binary to /sdcard (visible from app mount ns). Called via su -c.
ST=/sdcard/projects/Aegis/backend/node.bin
if [ -x /usr/bin/node ]; then SRC=/usr/bin/node
else SRC=$(command -v node 2>/dev/null)
fi
if [ -n "$SRC" ]; then
  if [ "$ST" -ot "$SRC" ] 2>/dev/null || [ ! -x "$ST" ]; then cp "$SRC" "$ST" && chmod 755 "$ST"; fi
  ls -la "$ST"
else
  echo NO-SRC
fi
