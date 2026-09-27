#!/bin/sh
# Instala el hook pre-commit de staleness de graphify para ESTE repo.
#
# Por qué este paso existe: /sdcard está montado noexec y su chmod es un no-op
# (el modo se queda en 660), así que git descarta cualquier hook que viva dentro
# del repo — se deduce con "hint: the hook was ignored because it's not set as
# executable" y el commit pasa igual. Verificado en Aegis el 2026-09-27 tanto con
# .git/hooks/pre-commit como con .githooks/pre-commit.
#
# Solución: la fuente del hook SÍ es versionable y vive en el repo
# (.githooks/pre-commit), pero la copia que git ejecuta se pone en un sitio
# ejecutable — por defecto ~/.git-hooks/<nombre-del-repo>/pre-commit — y
# core.hooksPath apunta ahí. El hook no depende del directorio desde el que se
# lance: usa git rev-parse --show-toplevel.
#
# Uso:   sh .githooks/install.sh
# Env:   GIT_HOOKS_HOME=/otra/ruta   (por defecto $HOME/.git-hooks)

set -eu

SRC_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=$(CDPATH= cd -- "$SRC_DIR/.." && pwd)
SRC="$SRC_DIR/pre-commit"

[ -f "$SRC" ] || { echo "no encuentro $SRC" >&2; exit 1; }
git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1 || {
    echo "$REPO no es un repositorio git" >&2; exit 1; }

HOME_HOOKS=${GIT_HOOKS_HOME:-$HOME/.git-hooks}
NAME=$(basename "$REPO")
DEST_DIR="$HOME_HOOKS/$NAME"

mkdir -p "$DEST_DIR"
cp "$SRC" "$DEST_DIR/pre-commit.new"
chmod 755 "$DEST_DIR/pre-commit.new"
mv "$DEST_DIR/pre-commit.new" "$DEST_DIR/pre-commit"

# Comprobación de verdad: si no es ejecutable, git lo ignoraría en silencio.
if [ ! -x "$DEST_DIR/pre-commit" ]; then
    echo "ERROR: $DEST_DIR/pre-commit no es ejecutable; git lo ignoraría." >&2
    echo "       probablemente $DEST_DIR está en un noexec. Pon GIT_HOOKS_HOME" >&2
    echo "       en una ruta ejecutable." >&2
    exit 1
fi

git -C "$REPO" config core.hooksPath "$DEST_DIR"

printf 'hook instalado\n'
printf '  fuente (versionable) : %s\n' "$SRC"
printf '  copia (la ejecuta git): %s\n' "$DEST_DIR/pre-commit"
printf '  core.hooksPath       : %s\n' "$(git -C "$REPO" config core.hooksPath)"
printf '  ejecutable           : %s\n' "$([ -x "$DEST_DIR/pre-commit" ] && echo sí || echo NO)"
