# Project handoff — 2026-11-28

Repository smaks190-crypto/auto-chess-, PR #5, branch chatgpt/nox-agent-core. Use git HEAD and GitHub CI as live truth.

## Current work
- Build #158 (e51dbab, CI 46109140449) passed and was installed on S24. APK hash and launch, saved model/history, microphone recording and Accessibility verified. Real Telegram typing test interrupted; not confirmed. New fix removes blanket refusal of compact agent commands, waits for panel hide, preserves permissions and lock/role checks. Unstructured Telegram open+write commands open only the verified Telegram app and ask for recipient/text, no guessed draft/send. New CI/device verification pending.
- Previous 15a62a6 microphone visualization improvement: CI #156 (46106784662) passed. Earlier 4133842 build #153 installed on S24; invocation starts recording, Back stops. Later voice/in-place/visualizer behavior not yet physically verified.
- Device-specific signed Dev APK update via existing workflow; do not install ephemeral CI debug APK.
- Issue #6: restricted lock-screen functionality (answer/public search/timer) is specified, not implemented.
- User's priority: fast voice execution in place; accurate outcomes, normal assistant UI, measured phone telemetry, avoid false success and unnecessary UI transitions.
- Next: resolve CI failure, verify latest build and current-chat draft using an empty test composer without sending; test spoken request/visualizer; then address timer and lock-screen scope.

- Wake handoff: device check found background_voice_active=false on build #158; no active foreground microphone. Fixed standby/assistant-panel microphone race in source, added bounded wake reservation and tests; clarified opt-in software detector UI. Actual recognition must be retested after update. Hardware SoundTrigger HAL Samsung SLSI present; no authorized custom N.O.X. DSP model/enrollment verified. Do not hide privacy indicators or enable background recording without user consent.

## Development handoff
Read AGENTS.md; architecture index in ARCHITECTURE.md, durable decisions in DECISIONS.md, remaining checks in KNOWN_ISSUES.md. Update this brief with exact evidence at the end of each task. Old transcripts are not runtime instructions.
