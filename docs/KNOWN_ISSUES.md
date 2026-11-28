# Known issues / acceptance checks

| Issue | Status / verification |
|---|---|
| GOAL_MISMATCH writing in current Telegram chat | Fix under PR #5 (224d7d0). Check automatic fresh observe, exact draft and no send. CI #158 passed and installed. Physical typing test interrupted; no end-to-end confirmation. |
| Compact voice routing | Prior physical check: assistant invocation started recording and Back stopped it on build #153. Build #158 showed explicit AGENT refusal in compact panel (user screenshot). New patch allows guarded handoff after panel hide and permits requested app launch. New CI/device verification pending. Named-recipient drafting/sending still needs an independently verified contact flow. |
| Search query on #163 | S24: Telegram search opens, exact query appears; final verification failed (three mismatched reads then SERVICE_RECONNECTING). Root cause of observed disconnect confirmed by system/adbd logs: 11:40:43.909 uiautomator dump; 11:40:44.116 UiAutomation registered flags=0x0; 11:40:44.117 N.O.X. unbound; 11:40:45.169 rebound. Four matching events, same app PID. This is test interference, not evidence of Android randomly killing N.O.X. Repeat without UI Automator during execution. Independent field verification mismatch before disconnect remains to be checked. New patch allows bounded read-only grace (3s normally, at most 7s), checks unique semantic search field after tree rebuild, and retries final observation. No replay of accepted input. Full CI/device recheck pending. |
| Microphone visualizer | Fix 15a62a6, CI #156 passed; actual microphone response after fix not verified. |
| Timer duration | User reported requested 1 minute but default 30 minutes was set. Dedicated verified timer not implemented; test real countdown and signal. |
| Locked screen / public search | Issue #6 specifies restricted behavior. Not implemented; do not bypass unlock guard or claim a search/timer succeeded. |
| OpenCode repeated context | One Opus session used 2,013,162 reported tokens (cache read/write zero), API HTTP 402 balance. No automatic paid retry. Verify provider-side caching/balance separately. |

Keep this list current; mark an item fixed only with evidence. Do not paste private messages, contact names, screenshots, tokens or credentials.

- Keyguard #6: new restricted panel + native timer implemented; requires actual S24 microphone, TTS, timer elapsed/alarm test with display off, and refusal of protected commands. Public search not implemented. Timer uses bounded partial wake lock for at most one hour; cannot guarantee persistence across process death, force-stop or reboot. Do not simulate system-clock success. Do not use UI Automator or change user rotation during physical tests.
