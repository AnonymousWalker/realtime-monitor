# Realtime Monitor

A baby monitor Android app that streams real-time video and audio over your local WiFi network. Connect from any browser on the same network — similar to DroidCam.

## Features

- **Real-time MJPEG video** streaming from device camera
- **Live audio** streaming (WAV/PCM over HTTP)
- **Browser-based client** — no app needed on the viewing side
- **Zoom** control via slider
- **Rotate** video feed (90° increments)
- **Flash/torch** toggle
- **Front/back camera** switching
- Dark-themed web UI optimized for night-time baby monitoring

## How it works

1. Install and launch the app on an Android phone
2. Grant camera and microphone permissions
3. Tap **Start Streaming**
4. Open the displayed URL (e.g. `http://192.168.86.195:4747/`) in any browser on the same WiFi network
5. Monitor your baby with live video and audio

## Building

```bash
# Requires JDK 17+ and Android SDK (platform 34, build-tools 34.0.0)
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Testing

```bash
./gradlew test        # Unit tests
./gradlew lint        # Android lint
```

## Tech Stack

- **Kotlin** — Android app
- **CameraX** — Camera capture (Preview + ImageAnalysis)
- **NanoHTTPD** — Embedded HTTP server
- **MJPEG** — Motion JPEG video streaming (works natively in browsers)
- **WAV/PCM** — Audio streaming

## Project Structure

```
app/src/main/
├── java/com/realtimemonitor/
│   ├── MainActivity.kt          # Main UI, permissions, start/stop
│   ├── camera/
│   │   └── CameraHelper.kt      # CameraX + AudioRecord wrapper
│   └── server/
│       ├── StreamingServer.kt    # HTTP server (NanoHTTPD)
│       └── WavHeader.kt         # WAV header generation
├── assets/
│   └── index.html               # Browser-based monitoring client
└── res/                          # Layouts, strings, themes
```
