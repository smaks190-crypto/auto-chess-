# N.O.X. Android — agent working agreement

This repository is smaks190-crypto/auto-chess-, working branch chatgpt/nox-agent-core, PR #5. Do not use N.O.X.-Project (separate project) as Android source. Code and verified CI/device evidence outrank old documentation.

## Start a task
1. Read docs/PROJECT_STATE.md (short handoff). Read only the relevant section of docs/ARCHITECTURE.md, docs/DECISIONS.md or docs/KNOWN_ISSUES.md.
2. Check current commit and worktree. Use GitHub MCP (nox_github) for PR, issue, CI and file history; use local source for edits. Sync only a clean worktree, fast-forward only (nox-dev sync).
3. Inspect targeted symbols and ranges using grep/read. Do not read entire long files or unrelated designs. Avoid duplicate reads and redundant planning. For small fixes, edit immediately after the cause is established.
4. Add focused regression tests; review diff, run nox-dev check. Use nox-dev submit for scoped commits/push to working PR; check CI. Use approved device QA only when relevant. Do not claim tests or installation without evidence.
5. Update PROJECT_STATE and any materially changed decision/issue before submitting. Record exact commit/run IDs, actual results, remaining blockers and next step. Keep handoff under 100 lines; move older decisions to DECISIONS. New unrelated task = fresh session + handoff; continue a session only for the same task. Never copy entire transcripts into project memory.

## Safety and resources
Never reveal credentials, private screen contents or messages in prompts, logs or commits. Preserve existing permissions, lock restrictions, user veto, fresh observations and verified postconditions. No force push, main push, workflow changes or arbitrary shell/device access. Do not send real messages in tests. During a running N.O.X. task, NEVER use adb shell uiautomator dump or default UiAutomation: flags=0 suppresses Accessibility services and invalidates the test. Use adb screencap, passive logcat and dumpsys instead; if instrumentation is required, explicitly use FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES and verify the N.O.X. service stays bound. Inspect semantic UI only after the task ends and do not treat that induced disconnect as an app fault.
Design references are for explicit UI design tasks only. Context7 only when API documentation is necessary. No gratuitous subagents or full-tree scans. Keep planning and reports compact. If an agent is interrupted, preserve worktree and summarize the specific remaining change before resuming. Never restart a failed paid request without assessing the error and remaining quota.
