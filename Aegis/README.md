# Aegis — Mobile Development Hub

Aegis is a self-contained AI orchestration platform running entirely from an Android device (POCO F3, Android 15, root Magisk, Ubuntu chroot).

## Architecture

- **Backend**: Node.js orchestrator (port 8765) with modular adapters, workflow engine, agent system, and skill manager
- **App**: Jetpack Compose Android app (`com.aegis.hub`) with Control Center, Project Workspace, Skill Manager, and Workflow Runner
- **Agents**: Specialized AI agents (Research, Architect, Auditor) operating via artifact-driven communication
- **Skills**: Graphify, opencode-mem, and extensible skill engine
- **Workflows**: YAML DAG-based workflow engine for autonomous multi-agent pipelines

## Project Structure

```
Aegis/
├── backend/     # Node.js Hub orchestrator
├── app/         # Android Jetpack Compose app
├── agents/      # Agency agents
├── docs/        # Architecture, QA reports, contracts
└── .hub/        # Project metadata
```

## Stack

Android 15 · Jetpack Compose · Node.js v24 · Ubuntu chroot · Magisk root · OpenCode · Antigravity · Artemis · agency-agents

## Status

![Build](https://github.com/fakekun420-ui/aegis/actions/workflows/build-apk.yml/badge.svg)

`v1.0.0-aegis-complete` — Backend phases 1-10 complete. Android app Phase 11 complete.
