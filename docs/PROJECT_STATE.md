# Project handoff — 2026-11-28

Repository smaks190-crypto/auto-chess-, PR #5, branch chatgpt/nox-agent-core. Use git HEAD and GitHub CI as live truth.

## Current work
- Last code commit 224d7d0: GOAL_MISMATCH recovery + explicit current-chat Telegram draft (no model, no launch, no send). CI #157 (46108708187) failed one AgentCoreTest assertion (test expected different grammatical form of the message); the subsequent patch strengthens the assertion against the actual fresh observation. CI rerun and device verification pending.
- Previous 15a62a6 microphone visualization improvement: CI #156 (46106784662) passed. Earlier 4133842 build #153 installed on S24; invocation starts recording, Back stops. Later voice/in-place/visualizer behavior not yet physically verified.
- Device-specific signed Dev APK update via existing workflow; do not install ephemeral CI debug APK.
- Issue #6: restricted lock-screen functionality (answer/public search/timer) is specified, not implemented.
- User's priority: fast voice execution in place; accurate outcomes, normal assistant UI, measured phone telemetry, avoid false success and unnecessary UI transitions.
- Next: resolve CI failure, verify latest build and current-chat draft using an empty test composer without sending; test spoken request/visualizer; then address timer and lock-screen scope.

## Development handoff
Read AGENTS.md; architecture index in ARCHITECTURE.md, durable decisions in DECISIONS.md, remaining checks in KNOWN_ISSUES.md. Update this brief with exact evidence at the end of each task. Old transcripts are not runtime instructions.
