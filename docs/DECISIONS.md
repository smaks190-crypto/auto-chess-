# Durable decisions

- D01: Keep Android work in auto-chess- / chatgpt/nox-agent-core / PR #5; do not rewrite working app or start a new repo.
- D02: Preserve existing Obsidian UI, local Vosk and LiteRT-LM. Use design references only for UI tasks.
- D03: System assistant starts listening on explicit invocation; finalized recognized commands may run in place. Preserve current screen; do not open arbitrary apps from compact mode.
- D04: Agent success requires independently verified observed postconditions. Stale node IDs require fresh observe and replanning; no automatic replay of uncertain mutations. Preserve authorization, cancellation, user confirmation and locked-device restrictions.
- D05: Current-chat Telegram draft: exact text in unambiguous composer, no app launch, no automatic send or overwriting an existing draft. Explicit send not implemented. No real outbound messages in automated tests.
- D06: Locked-screen goal (issue #6): isolated public answer, public web search and verified timer only; no general device control or private data. Requirements only until implemented and tested.
- D07: Build via Android CI. Dev APK artifact is locally re-signed with persistent existing key; disposable CI debug APK is not an update. Preserve app data and verify signature/package. Distinguish CI from physical tests.
- D08: Agent memory is versioned Markdown, not full transcript injection. Use existing local repo plus scoped GitHub MCP reads and branch-only submit; never expose credentials. Use one coherent task session, then concise handoff. Cache accounting from Tokenn must be verified, never assumed.
