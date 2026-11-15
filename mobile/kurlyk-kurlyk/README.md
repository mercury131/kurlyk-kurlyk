# Kurlyk Kurlyk

Private, end-to-end encrypted Android messenger built with Kotlin, Jetpack Compose, Material 3, Room, OkHttp, Hilt, and WebRTC.

## Server Information

Kurlyk Kurlyk connects to the **`securegate`** relay server running on **port 443** (`https://{domain}` / `wss://{domain}/chat/ws`). All messages, files, and profile photos are encrypted client-side using **AES-256-GCM** with keys derived via **HMAC-SHA256** from your shared server secret key. Message history is stored strictly on-device in Room.

## How to Build

### Prerequisites
- Android Studio (latest stable) or Gradle CLI with JDK 17+
- Android SDK (compileSdk 36 / minSdk 26)

### Build Debug APK
```bash
gradle :app:assembleDebug
```
The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Run Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```

## How to Sign In

1. Open **Aurora Messenger** on your Android device.
2. Enter the **Server domain** (e.g. `chat.example.com` — no `https://` or path required; the client strips them automatically).
3. Enter the **Secret key** provided by your `securegate` server administrator.
4. Choose your country code (default `+7`) and enter your **Phone number** (normalized to E.164).
5. Enter **Your name** (display name shown to other members).
6. Tap **Connect**.

> **Testing on a local/staging server with a self-signed certificate:** Tap the sign-in subtitle (or App Version in Settings) 7 times to reveal the developer switch *"Allow self-signed certificate (testing)"* (off by default).
