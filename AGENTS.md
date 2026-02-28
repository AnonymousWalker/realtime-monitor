# AGENTS.md

## Cursor Cloud specific instructions

This is a Kotlin Android project (baby monitor / realtime camera streaming app).

### Prerequisites

The build requires:
- **JDK 17+** (JDK 21 works fine)
- **Android SDK** with platform `android-34` and `build-tools;34.0.0` installed at `/opt/android-sdk`
- A `local.properties` file pointing to the SDK: `sdk.dir=/opt/android-sdk`

### Key commands

| Task | Command |
|------|---------|
| Build debug APK | `./gradlew assembleDebug` |
| Run unit tests | `./gradlew test` |
| Run lint | `./gradlew lint` |
| Full check | `./gradlew assembleDebug test lint` |

All Gradle commands require `ANDROID_HOME=/opt/android-sdk` in the environment.

### Architecture overview

- `MainActivity` — camera preview + start/stop streaming controls
- `CameraHelper` — CameraX wrapper: captures YUV frames → JPEG, manages audio via AudioRecord
- `StreamingServer` — NanoHTTPD HTTP server on port 4747; serves MJPEG video, WAV audio, and control API
- `WavHeader` — generates WAV file headers for audio streaming
- `app/src/main/assets/index.html` — browser-based monitoring client (dark-themed)

### Workflow preferences

- **Always provide the debug APK** after each implementation. Copy it to `/opt/cursor/artifacts/` and create a GitHub release via `gh release create` so the user can download it immediately.

### Non-obvious notes

- The Gradle wrapper (`gradlew`) must be generated once via `gradle wrapper --gradle-version 8.4` if the JAR is missing.
- The app cannot be *run* in this VM (no Android device/emulator with KVM). Build and unit test verification is the limit of CI-style testing.
- `local.properties` is in `.gitignore`; each environment must create it pointing to its SDK path.
- NanoHTTPD 2.3.1 is pulled from Maven Central; it is a single-JAR embedded HTTP server with no transitive dependencies.
