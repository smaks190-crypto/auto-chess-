# N.O.X. Obsidian — approved design draft (2026-11-27)

The exact approved HTML is bundled at app/src/main/assets/obsidian/index-matrix.html (SHA-256 35c12ed4ee76f276639cbe8258490b65f35e1ad6fdbc24b54fd44f29219cdb07). Open Obsidian from the native chat toolbar while the agent is idle. Exit returns to the real chat. This is an intentionally isolated design preview, not a replacement for the native assistant. The demo does not receive model, agent, camera, microphone or storage permissions; it has no native bridge, external navigation or network access. File input and real microphone/camera acquisition are not connected. Preserve the existing foreground service, model catalog and verification pipeline.

Future native integration: build state adapters from NoxService, ModelDownload, LocalModel, AgentCore, GoalState and EffectCheck; map only supported thinking levels (existing boolean deep with 1024-token budget); do not invent hardware telemetry or successful actions. Make the native matrix and four screens accessible, throttle animation, and verify the agent regressions separately.

Acceptance: CI unit tests, lint, assembleDev; locally sign with the existing dev key; install in place without clearing data; verify native chat starts, Obsidian opens, drag the matrix, return to native chat. No main-branch merge of unrelated stacked PRs.
