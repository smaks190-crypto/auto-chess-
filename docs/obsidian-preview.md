# N.O.X. Obsidian — approved design draft (2026-11-27)

The exact approved HTML is bundled at app/src/main/assets/obsidian/index-matrix.html (SHA-256 4a5cc74d6bbd23f9ee5c395431cd73bdbd484bc6debdfba1114283589e701177). Open Obsidian from the native chat toolbar while the agent is idle. Exit returns to the real chat. This is an intentionally isolated design preview, not a replacement for the native assistant. The demo does not receive model, agent, camera, microphone or storage permissions; it has no native bridge, external navigation or network access. File input and real microphone/camera acquisition are not connected. Preserve the existing foreground service, model catalog and verification pipeline.

Future native integration: build state adapters from NoxService, ModelDownload, LocalModel, AgentCore, GoalState and EffectCheck; map only supported thinking levels (existing boolean deep with 1024-token budget); do not invent hardware telemetry or successful actions. Make the native matrix and four screens accessible, throttle animation, and verify the agent regressions separately.

Acceptance: CI unit tests, lint, assembleDev; locally sign with the existing dev key; install in place without clearing data; verify native chat starts, Obsidian opens, drag the matrix, return to native chat. No main-branch merge of unrelated stacked PRs.

## Hold-to-select update

The approved combined Claude/Qwen/GPT preview replaces the original asset. Hold the model/effort chip for 340 ms, drag into the matrix and release to commit and close. Releasing outside cancels. Tap still opens the accessible dialog. Includes selected visual polish, action timestamps, focus and touch-target improvements, and microphone animation throttling. Verified the HTML syntax and hold/release/cancel/tap on Galaxy S24 in Samsung Internet. Native WebView regression, TalkBack and real microphone testing remain pending; microphone is deliberately denied in the isolated APK preview. The native agent and real inference settings are unchanged.
