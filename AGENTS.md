## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## decisiones F6–F14 (medidas en dispositivo, 2026-10-08/09)

- Guarda anti-duplicado con corchete (`[o]pencode(.exe)? serve --service`); contar listeners `:49374`, no PIDs. Cerrojo `mkdir` 60 s (flock toybox no sirve).
- `aegis-serve.sh` (asset) fija `HOME/PATH/SHELL` del chroot; verificar el asset del APK instalado, no solo el repo. Chats globales nacen en `/sdcard/projects`.
- Catálogo de modelos sin recorte de proveedor + guardia de envío honesto (`motivoModeloNoDisponible`). Hoja de modelos con `LazyColumn` + buscador (`filtrarPorTexto`).
