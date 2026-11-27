# Obsidian native integration

Version 0.4.24 moves the approved design direction to MainActivity rather than a separate WebView. The reference HTML lives under docs/design and is not packaged. All existing NoxService permission, model, attachment, voice, background, history and agent controls remain native.

The four tabs expose real chat, real Vision attachment and live perception, current agent trace and stop controls, and model/service settings. The native matrix holds for 340 ms, previews a model/effort cell, commits on release inside the grid, cancels outside; ordinary click opens a keyboard-accessible selection dialog. Model changes use existing model selection and download confirmation. Chat effort budgets: 0, 256, 1024; Auto uses 0 for short text and 256 for long text or image. Agent planner and voice-originated requests retain prior policies. These are not model benchmark results; runtime compatibility must be tested per model.

The native matrix is a first visual translation, not a pixel-identical reproduction. Current-session agent trace exposes existing verified outcomes and step types, not a fabricated ledger. No fake resource readings. Voice uses existing recognition and TTS, not an invented live spectrum.

Verification: pure policy checks were run on JVM; Android CI and on-device regression are required before declaring complete.
