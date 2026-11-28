# Known issues / acceptance checks

| Issue | Status / verification |
|---|---|
| GOAL_MISMATCH writing in current Telegram chat | Fix under PR #5 (224d7d0). Check automatic fresh observe, exact draft and no send. CI #157 failed one incorrect text assertion; subsequent patch verifies the actual fresh observation. CI rerun pending; no new APK installed yet. |
| Compact voice routing | Prior physical check: assistant invocation started recording and Back stopped it on build #153. Later in-place changes need end-to-end spoken recognition / execution and current-window checks. |
| Microphone visualizer | Fix 15a62a6, CI #156 passed; actual microphone response after fix not verified. |
| Timer duration | User reported requested 1 minute but default 30 minutes was set. Dedicated verified timer not implemented; test real countdown and signal. |
| Locked screen / public search | Issue #6 specifies restricted behavior. Not implemented; do not bypass unlock guard or claim a search/timer succeeded. |
| OpenCode repeated context | One Opus session used 2,013,162 reported tokens (cache read/write zero), API HTTP 402 balance. No automatic paid retry. Verify provider-side caching/balance separately. |

Keep this list current; mark an item fixed only with evidence. Do not paste private messages, contact names, screenshots, tokens or credentials.
