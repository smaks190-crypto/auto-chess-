# Android architecture — navigation index

Actual project: Kotlin, Android SDK 36 build, JDK 17 / Gradle 8.13 in CI. Package dev.nox.local; local Dev variant dev.nox.local.dev. Target physical phone Samsung S24 SM-S921B. Paths below under app/src/main/java/dev/nox/local/ unless specified.

| Area | Start here |
|---|---|
| Main chat, WebView/native bridge and foreground routing | MainActivity.kt, ObsidianHost.kt, ObsidianCommand.kt; app/src/main/assets/obsidian/index.html |
| System assistant and compact voice panel | NoxSystemAssistant.kt, AssistantVoiceCommand.kt, AssistantLaunchGate.kt |
| Service, request lifecycle, model and voice | NoxService.kt, RequestRouter.kt, LocalModel.kt, PlannerSession.kt |
| Local Vosk ASR, voice amplitude and wake | SilentRecognizer.kt, VoiceMeter.kt, WakeWordPolicy.kt, VoiceLoopPolicy.kt |
| Agent planning and tool protocol | AgentCore.kt, AgentProtocol.kt, GoalState.kt, SearchNavigation.kt, TelegramDraft.kt |
| Android accessibility and execution | AndroidAgentTools.kt, NoxNavigationService (locate declaration), UiSafety.kt, EffectCheck.kt, AgentPermissionPolicy.kt, AgentActionGate.kt |
| Device telemetry and model storage | DeviceTelemetry.kt, ModelStorage.kt, ModelDownload.kt, ResidentModel.kt |
| Visual observation | PerceptionService.kt, PerceptionBuffer.kt, UiChangeRelay.kt |
| Tests / CI | app/src/test/java/dev/nox/local/, .github/workflows/android.yml (read-only for agents) |

Model-based ordinary requests are distinct from narrow deterministic, independently verified operations. AndroidAgentTools owns fresh screen snapshots and actual actions; the model does not authorize them. The compact panel does not open N.O.X. chat automatically. Explicit unlocked device-control commands hand off to the guarded agent after the panel has been hidden; only requested app navigation is allowed. Ordinary chat answers remain in the panel. Exact supported current-chat Telegram drafting uses observe → type → independent verification, no model call. See source for lifecycle and exact predicates.

Older README.md and docs/agent-core.md describe earlier implementations; consult code and PROJECT_STATE before relying on them. Existing docs/adb-runner.md, docs/s24-check-ru.md, docs/obsidian-preview.md contain specialized workflows.
