# KeePass Sync — APK Build Guide

Instructions for building the Android application package (APK) for installation on physical hardware.

---

## 1. Setup Build Environment (Linux)

Before building, you need to install the necessary toolchain.

### 1a. Install Java 21 (JDK)
Android build systems require a specific JDK version. For this project, **Java 21** is required.
```bash
sudo apt update
sudo apt install openjdk-21-jdk
```
Verify the installation:
```bash
java -version
```

### 1b. Install Android SDK Command-Line Tools
If you don't want to install the full Android Studio (which is ~1GB+), you can install just the command-line tools:

1. Create a directory for the SDK:
   ```bash
   mkdir -p ~/Android/Sdk
   ```
2. Download the "Command line tools only" package for Linux from the [Android Studio Downloads](https://developer.android.com/studio#cmdline-tools) page.
3. Unzip the downloaded file into `~/Android/Sdk/cmdline-tools/`. 
   **Note**: The resulting structure should be `~/Android/Sdk/cmdline-tools/latest/bin/...`. If the zip contains a `cmdline-tools` folder, rename it to `latest` and move it under a parent `cmdline-tools` folder.

### 1c. Set Environment Variables
Add the following to your `~/.bashrc` or `~/.zshrc`:
```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```
Then reload your shell: `source ~/.bashrc`

### 1d. Install SDK Components
Run these commands to install the specific versions used by this app:
```bash
# Accept licenses first
yes | sdkmanager --licenses

# Install Platform and Build Tools
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

---

## 2. Prerequisites (Summary)

- **Java 21 (JDK)** installed and in your PATH.
- **Android SDK** (API 35) installed.
- **Gradle** (or use the included `./gradlew` wrapper).

> [!TIP]
> If you are using Windows, replace `./gradlew` with `gradlew.bat`.

---

## 2. Build Debug APK (Unsigned)

The easiest way to test on a device is to build a Debug APK. These are automatically signed with a generic debug key.

### Steps:
1. Open a terminal in the `android-app/` directory.
2. Run the build command:
   ```bash
   ./gradlew assembleDebug
   ```
3. Find your APK:
   `app/build/outputs/apk/debug/app-debug.apk`

### Installation:
Connect your phone via USB (with Developer Options and USB Debugging enabled) and run:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. Build Release APK (Signed)

For a performance-optimized build that adheres to security standards, you must build and sign a Release APK.

### Step 3a: Generate a Signing Key (if you don't have one)

If you don't have a keystore file, generate one using `keytool`:

```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-alias
```
*Follow the prompts to set a password and company details.*

### Step 3b: Build the Signed APK

You can build the release APK and sign it via the command line by passing the keystore properties:

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=/path/to/my-release-key.jks \
  -Pandroid.injected.signing.store.password=YOUR_STORE_PASSWORD \
  -Pandroid.injected.signing.key.alias=my-alias \
  -Pandroid.injected.signing.key.password=YOUR_KEY_PASSWORD
```

### Step 3c: Location of the APK

The signed, optimized release APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

---

## 4. Troubleshooting

### "Permission Denied" for gradlew
If the script is not executable, run:
```bash
chmod +x gradlew
```

### "SDK location not found"
Create a file named `local.properties` in the `android-app/` folder and add the path to your Android SDK:
```properties
sdk.dir=/home/your-user/Android/Sdk
```

### ProGuard / R8 Issues
The release build uses R8 for minification and obfuscation. if the app crashes only in the release build, check the `proguard-rules.pro` file or ensure that necessary network models are kept. Since we use `OkHttp`, it is already handled by default rules usually, but check server-side logs if JSON parsing fails.
