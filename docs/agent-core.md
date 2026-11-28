# N.O.X. local agent core

Replace regex-based dispatch for ordinary requests with model-selected tools. Keep Vosk, existing UI, local weights and history.
- A local model produces one structured action at a time. Only an explicit action envelope may invoke a tool.
- Tools: list/launch installed apps, observe accessible active window, click and edit observed nodes, scroll, home/back, volume. No shell commands, network, or root.
- Observe a fresh screen after each action. Bind node IDs to the observed screen and reject changed targets. Exclude password fields, N.O.X. itself and sensitive system dialogs. No background screen collection outside a user-initiated task.
- Navigation is automatic. Confirm every click or text change via a visible user-only control before executing. An approval is scoped to a single exact node/action; denial stops the task. No silent sending, payment or permission changes.
- Limit step count/time; cancellation, lock and service disconnect stop execution. Report observed tool results, not invented completion.
- Preserve image chat separately; all normal text/voice requests go through the model, including simple app launch.
- Add JVM tests for action envelope validation and bounded agent loop; verify Android CI and manually test Telegram search, drafting and explicit send approval on S24. Real accessibility trees vary; unsupported canvas-only UIs are not guaranteed.
