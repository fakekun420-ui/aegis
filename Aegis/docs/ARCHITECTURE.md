# Arquitectura del Sistema - Aegis

> **Nota de Migración:** Este proyecto fue migrado y reestructurado a partir de los repositorios independientes `opencode-companion` (backend Node.js) y `opencode-companion-apk` (frontend Android Jetpack Compose) en un repositorio unificado.

## Visión General
Aegis es un Mobile Development Hub diseñado para ejecutarse y orquestarse en entornos móviles avanzados (Android 15, Magisk root, Ubuntu chroot).

## Estructura del Monorepo / Hub
```
Aegis/
├── backend/    # Servidor Node.js, API hub, gestión de proveedores y procesos en background
├── app/        # Cliente nativo Android (Jetpack Compose / Gradle)
├── .hub/       # Metadatos del proyecto y configuración del espacio de trabajo
├── docs/       # Documentación de arquitectura, APIs y contratos
└── agents/     # Repositorio de agentes autónomos y roles especializados
```

## Componentes Principales
- **Backend (`./backend`)**:
  - `server.js`: API y servidor HTTP principal.
  - `providers.js`: Integración de modelos y proveedores de IA.
  - `keepalive.sh`: Script watchdog/daemon de persistencia y monitorización de servicios.
- **App (`./app`)**:
  - Aplicación Android desarrollada en Kotlin y Jetpack Compose.
  - Soporte de ejecución bajo entorno root y utilidades de automatización móvil.
- **Hub Metadata (`./.hub`)**:
  - `project.json`: Declaración de rutas, dependencias de espacio de trabajo y metadatos.
- **Agents (`./agents`)**:
  - Catálogo de personas y agentes especializados (Agency Agents).
