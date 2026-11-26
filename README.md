# N.O.X. — Android / ChatGPT implementation

Independent implementation for [issue #1](../../issues/1).

## First vertical slice (experimental)
- Kotlin Android activity, offline model import, text and imported-image inference through LiteRT-LM.
- Push-to-talk via Android on-device speech recognition when available; Russian offline TTS when installed.
- No INTERNET permission, no ADB/root/Termux needed at runtime. No action execution in this slice.
- Model file: [Gemma 4 E2B IT LiteRT-LM](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm). Download separately, review license and verify checksum from distributor, then import via Android document picker. Model is not bundled. Allow enough free space for a private copy (~2.6 GB).
- Select CPU/GPU before importing/loading; image path uses GPU vision backend. Device support must be measured.

## Build
JDK 17, Gradle 8.13, Android SDK 36. Run `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. CI publishes debug APK on success. Gradle wrapper not yet bundled.
 
## Not implemented / not verified
Live screen capture, autonomous actions, overlay, persistent service, wake word, owner verification, parallel speech, Magic Chess phase logic/strategy, hardware performance, and physical-device tests. Imported-image analysis is not live vision. Native Views are used for the first slice; Compose migration is optional after hardware validation. No claim of complete product or game wins.
