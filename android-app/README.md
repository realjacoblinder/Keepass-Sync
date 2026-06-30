# KeePass Sync — Android App

Native Android client for the KeePass Sync server. Connects to the backend to upload, merge, and download `.kdbx` database files.

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **OkHttp** for networking
- **Storage Access Framework** for file picking and saving
- Minimum SDK: **26** (Android 8.0)

## Building

### Option A: Android Studio (recommended)

1. Open the `android-app/` folder in Android Studio.
2. Android Studio will auto-generate the Gradle wrapper and sync dependencies.
3. Build & run on a device or emulator.

### Option B: Command line

```bash
# Generate the Gradle wrapper (requires Gradle installed globally)
cd android-app
gradle wrapper

# Build the debug APK
./gradlew assembleDebug

# The APK will be at app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Launch the app.
2. Enter the URL of your KeePass Sync server (e.g., `http://192.168.1.100:3000`).
3. The app validates connectivity, then shows the sync dashboard.
4. **Upload & Sync:** Pick a `.kdbx` file, enter the master password, and tap "Sync Database."
5. **Download:** Tap "Download" to save the merged master database to your device.

## Notes

- The server URL is persisted in SharedPreferences and remembered across app launches.
- Cleartext HTTP traffic is allowed (`usesCleartextTraffic="true"`) for local network use. For production, use HTTPS.
- The uploaded and master databases must share the **same master password** (backend requirement).
