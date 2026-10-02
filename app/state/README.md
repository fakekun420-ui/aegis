# `app/state/` — datos de la app

**MEDIDO 2026-10-02.** Esta carpeta se creó al retirar el Hub. Antes, los datos de la app vivían
dentro de `backend/`, que es un error de diseño que solo se ve cuando el Hub desaparece: la app
leía de un directorio propiedad de otro componente.

## Qué hay aquí

| Fichero | Qué es | Quién lo usa |
|---|---|---|
| `bootstrap-state.json` | Estado del asistente de instalación (qué pasos están hechos) | `BootstrapNative` |
| `projects.json` | Registro de proyectos y sesión↔proyecto, formato heredado | `ProjectsStore` (como origen de migración) |
| `projects-store.json` | El mismo registro ya migrado al formato de la app | `ProjectsStore` |

## Por qué hay un fichero "legacy"

`ProjectsStore` y `BootstrapNative` tienen un camino de migración: si el fichero nuevo no está,
leen el antiguo. Eso parece redundante cuando los dos están aquí, y no lo es:

- Si un despliegue queda a medias (la app nueva instalada, los datos copiados a medias), la
  migración evita que el estado desaparezca sin ruido.
- Si alguien borra el nuevo por accidente, se recupera del viejo.

La migración **devuelve `null` si falla y sigue su camino**. Una migración rota no puede romper la
app; si lo hiciera, estaríamos convirtiendo una mejora en un requisito.

## El token NO está aquí

Antes había un cuarto fichero, `.aegis_token`, que también vivía en `backend/`. **No se migró a
propósito**: era el secreto compartido entre la app y el Hub, y **sin Hub no hay a quién
autenticarle**. La contraseña que usa la app ahora es la del propio OpenCode, que vive en
`/root/.local/state/opencode/service.json` y la lee `Credentials` vía `su`.

`ApiClient` (el cliente del Hub) sigue en el código porque la costura lo conserva como destino de
las 49 funciones que aún delegan en el Hub. Sus peticiones fallarán —el puerto 8765 no está— pero
fallan igual con token o sin él, y por eso el token no se arrastra.

## Revertir

Si algún día hace falta el Hub, sus 53 ficheros de código están en
`/sdcard/projects/_tmp/hub-retirado-2026-10-02/`, fuera de git, y los datos de esta carpeta son los
mismos que tenía.
