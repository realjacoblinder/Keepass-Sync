# KeePass Sync Android App — Testing Plan

Complete step-by-step instructions for building, deploying, and testing the Android app on a Linux machine using the Android emulator.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Environment Setup](#2-environment-setup)
3. [Build the App](#3-build-the-app)
4. [Start the Backend Server](#4-start-the-backend-server)
5. [Create Test Data](#5-create-test-data)
6. [Launch the Emulator](#6-launch-the-emulator)
7. [Install the App](#7-install-the-app)
8. [Test Cases](#8-test-cases)
   - [TC-01: Server Setup — Valid URL](#tc-01-server-setup--valid-url)
   - [TC-02: Server Setup — Invalid URL](#tc-02-server-setup--invalid-url)
   - [TC-03: Server Setup — Unreachable Server](#tc-03-server-setup--unreachable-server)
   - [TC-04: Server Setup — URL Persistence](#tc-04-server-setup--url-persistence)
   - [TC-05: Server Setup — Auto HTTP Prefix](#tc-05-server-setup--auto-http-prefix)
   - [TC-06: Sync Screen — Master Name Validation](#tc-06-sync-screen--master-name-validation)
   - [TC-07: Sync — First Upload (Create Master)](#tc-07-sync--first-upload-create-master)
   - [TC-08: Sync — Merge Upload](#tc-08-sync--merge-upload)
   - [TC-09: Sync — Wrong Password](#tc-09-sync--wrong-password)
   - [TC-10: Sync — No File Selected](#tc-10-sync--no-file-selected)
   - [TC-11: Download — Successful Download](#tc-11-download--successful-download)
   - [TC-12: Download — No Master Exists](#tc-12-download--no-master-exists)
   - [TC-13: Status — Auto-Refresh on Name Change](#tc-13-status--auto-refresh-on-name-change)
   - [TC-14: Change Server](#tc-14-change-server)
   - [TC-15: Dark Mode](#tc-15-dark-mode)
   - [TC-16: Keyboard & Scroll Behavior](#tc-16-keyboard--scroll-behavior)
9. [Cleanup](#9-cleanup)

---

## 1. Prerequisites

| Tool | Purpose | Install |
|------|---------|---------|
| **Java 21+** (JDK) | Gradle/Android build toolchain | `sudo apt install openjdk-21-jdk` |
| **Android SDK Command-Line Tools** | `sdkmanager`, `avdmanager`, `emulator`, `adb` | [developer.android.com/studio#cmdline-tools](https://developer.android.com/studio#cmdline-tools) |
| **Docker + Docker Compose** | Run the backend server | [docs.docker.com/engine/install](https://docs.docker.com/engine/install/) |
| **KeePassXC** (CLI) | Create test `.kdbx` files | `sudo apt install keepassxc` |
| **KVM** (recommended) | Hardware-accelerated emulator | `sudo apt install qemu-kvm` |

> [!NOTE]
> If you have **Android Studio** installed, it bundles all SDK tools. You can skip the manual SDK setup and use the IDE directly.

---

## 2. Environment Setup

### 2a. Android SDK (if not using Android Studio)

```bash
# Set up environment variables (add to ~/.bashrc or ~/.zshrc)
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# Install required SDK packages
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" "system-images;android-35;google_apis;x86_64"
```

### 2b. Create an Android Virtual Device (AVD)

```bash
# Create an AVD named "test_device" with a Pixel 9 profile
avdmanager create avd \
  --name "test_device" \
  --package "system-images;android-35;google_apis;x86_64" \
  --device "pixel_9"
```

### 2c. Verify KVM access (for emulator acceleration)

```bash
# Check KVM is available
kvm-ok
# or
ls -la /dev/kvm

# If permission denied, add your user to the kvm group:
# sudo usermod -aG kvm $USER
# Then log out and back in.
```

---

## 3. Build the App

```bash
cd /home/yaakov/Keepass-Sync/android-app

# Generate the Gradle wrapper if it doesn't exist
# (Skip if opening in Android Studio — it handles this automatically)
gradle wrapper

# Build the debug APK
./gradlew assembleDebug

# Verify the APK was created
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

**Expected:** Build succeeds with no errors. APK file exists at the path above.

> [!TIP]
> If you encounter SDK-related errors, create a `local.properties` file in the `android-app/` directory:
> ```
> sdk.dir=/home/yaakov/Android/Sdk
> ```

---

## 4. Start the Backend Server

The Android app needs the KeePass Sync backend running and reachable from the emulator.

```bash
cd /home/yaakov/Keepass-Sync

# Start the backend in Docker
docker compose up -d

# Verify it's running
curl -s -X POST http://localhost:3000/api/status \
  -H "Content-Type: application/json" \
  -d '{"masterName":"master"}' | jq .
```

**Expected output:**
```json
{ "lastUpdated": null }
```

### Emulator Networking

The Android emulator maps the host machine's `localhost` to **`10.0.2.2`** from within the emulator. So:

| From Host | From Emulator |
|-----------|---------------|
| `http://localhost:3000` | `http://10.0.2.2:3000` |

You will use `http://10.0.2.2:3000` as the server URL inside the app.

---

## 5. Create Test Data

Create two test `.kdbx` files with different entries to verify merge behavior.

```bash
cd /home/yaakov/Keepass-Sync

# Create a directory for test files
mkdir -p test-data

# Create test database A with an entry
keepassxc-cli db-create test-data/test-db-A.kdbx -p <<< "testpassword123"
keepassxc-cli add test-data/test-db-A.kdbx -u "user_A" --url "https://site-a.com" "EntryFromA" -p <<< "testpassword123"

# Create test database B (clone A, then add a different entry)
cp test-data/test-db-A.kdbx test-data/test-db-B.kdbx
keepassxc-cli add test-data/test-db-B.kdbx -u "user_B" --url "https://site-b.com" "EntryFromB" -p <<< "testpassword123"
```

> [!NOTE]
> If `keepassxc-cli` is not available or the commands differ on your system, you can create test databases using the KeePassXC GUI application instead. Just create two `.kdbx` files with the password `testpassword123` and at least one unique entry in each.

### Push test files to the emulator

After the emulator is running (Step 6), push the files so the file picker can find them:

```bash
adb push test-data/test-db-A.kdbx /sdcard/Download/test-db-A.kdbx
adb push test-data/test-db-B.kdbx /sdcard/Download/test-db-B.kdbx
```

---

## 6. Launch the Emulator

```bash
# Start the emulator (headless option available for CI)
emulator -avd test_device &

# Wait for the device to fully boot
adb wait-for-device
adb shell getprop sys.boot_completed
# Wait until this returns "1"
```

> [!TIP]
> For a GUI window, just run `emulator -avd test_device`. For headless/CI use, add `-no-window -no-audio`.

---

## 7. Install the App

```bash
cd /home/yaakov/Keepass-Sync/android-app

# Install the debug APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Launch the app
adb shell am start -n com.keepasssync.app/.MainActivity
```

**Expected:** The app opens to the **Server Setup screen** showing a lock icon, "Connect to Server" heading, a URL text field, and a "Connect" button.

---

## 8. Test Cases

### TC-01: Server Setup — Valid URL

**Steps:**
1. In the URL field, type `http://10.0.2.2:3000`
2. Tap **Connect**

**Expected:**
- A loading spinner appears with "Connecting…" text
- After 1–2 seconds, the app navigates to the **Sync Screen**
- The top bar shows "KeePass Sync" with a back arrow
- A chip below the top bar shows `http://10.0.2.2:3000`
- The master name field defaults to `master`

---

### TC-02: Server Setup — Invalid URL

**Steps:**
1. In the URL field, type `not a real url !!!`
2. Tap **Connect**

**Expected:**
- A loading spinner appears briefly
- An error message appears below the text field: "Connection failed: …" or "Could not reach server…"
- The app stays on the Server Setup screen

---

### TC-03: Server Setup — Unreachable Server

**Steps:**
1. In the URL field, type `http://10.0.2.2:9999` (a port with no server)
2. Tap **Connect**

**Expected:**
- A loading spinner appears for several seconds (up to the 30s connect timeout)
- An error message appears: "Could not reach server at http://10.0.2.2:9999"
- The app stays on the Server Setup screen

---

### TC-04: Server Setup — URL Persistence

**Steps:**
1. Connect successfully to `http://10.0.2.2:3000` (per TC-01)
2. Force-close the app:
   ```bash
   adb shell am force-stop com.keepasssync.app
   ```
3. Relaunch the app:
   ```bash
   adb shell am start -n com.keepasssync.app/.MainActivity
   ```

**Expected:**
- The app opens directly to the **Sync Screen** (skips Server Setup)
- The server URL chip shows `http://10.0.2.2:3000`

---

### TC-05: Server Setup — Auto HTTP Prefix

**Steps:**
1. If on the Sync Screen, tap the **back arrow** to return to Server Setup
2. Clear the URL field and type `10.0.2.2:3000` (no `http://` prefix)
3. Tap **Connect**

**Expected:**
- The app auto-prepends `http://` and connects successfully
- Navigates to the Sync Screen
- Server URL chip shows `http://10.0.2.2:3000`

---

### TC-06: Sync Screen — Master Name Validation

**Steps:**
1. On the Sync Screen, clear the master name field
2. Type `my database!` (contains spaces and special characters)

**Expected:**
- A red error border appears on the text field
- Supporting text shows: "Only alphanumeric characters and hyphens allowed"
- The **Sync Database** button is disabled (grayed out)

**Steps (continued):**
3. Clear and type `my-database` (valid characters)

**Expected:**
- Error styling disappears
- The "no master database found" warning appears in the download section (normal for a new name)

---

### TC-07: Sync — First Upload (Create Master)

**Precondition:** Ensure no master named `master` exists on the server:
```bash
rm -f /home/yaakov/Keepass-Sync/data/master.kdbx
```

**Steps:**
1. Set master name to `master`
2. Tap **Select .kdbx File**
3. In the system file picker, navigate to `Downloads` → select `test-db-A.kdbx`
4. The button text should change to `test-db-A.kdbx`
5. In the password field, type `testpassword123`
6. Tap **Sync Database**

**Expected:**
- A loading spinner appears with "Syncing…"
- After a few seconds, a **green success card** appears: "Master database created successfully"
- The password field is cleared
- The file selection is reset (button shows "Select .kdbx File" again)
- The download section now shows a **"Last updated: …"** timestamp instead of the "no database" warning
- The **Download** button becomes enabled

**Verify on server:**
```bash
ls -la /home/yaakov/Keepass-Sync/data/master.kdbx
# File should exist
```

---

### TC-08: Sync — Merge Upload

**Precondition:** A master DB exists from TC-07.

**Steps:**
1. Set master name to `master`
2. Tap **Select .kdbx File** → select `test-db-B.kdbx` from Downloads
3. Enter password: `testpassword123`
4. Tap **Sync Database**

**Expected:**
- Success card: "Database merged successfully"
- The "Last updated" timestamp refreshes to a more recent time

**Verify merge on server:**
```bash
# List entries in the merged master — should contain entries from both A and B
keepassxc-cli ls /home/yaakov/Keepass-Sync/data/master.kdbx <<< "testpassword123"
```
Expected output should include both `EntryFromA` and `EntryFromB`.

---

### TC-09: Sync — Wrong Password

**Steps:**
1. Tap **Select .kdbx File** → select `test-db-A.kdbx`
2. Enter password: `wrongpassword`
3. Tap **Sync Database**

**Expected:**
- A **red error card** appears with a message like: "Failed to decrypt uploaded database. Incorrect password?"
- The form is NOT reset (file and password remain for the user to correct)

---

### TC-10: Sync — No File Selected

**Steps:**
1. Ensure no file is selected (button shows "Select .kdbx File")
2. Enter a password
3. Observe the **Sync Database** button

**Expected:**
- The **Sync Database** button is disabled/grayed out
- The button cannot be tapped

---

### TC-11: Download — Successful Download

**Precondition:** A master DB named `master` exists on the server (from TC-07/TC-08).

**Steps:**
1. Ensure master name is `master`
2. Verify the "Last updated" timestamp is visible
3. Tap **Download 'master' DB**
4. A system save dialog appears — choose a location (e.g., Downloads) and confirm
5. The file name should default to `master.kdbx`

**Expected:**
- A loading spinner appears with "Downloading…"
- A **green success card** appears: "Database downloaded successfully!"
- The file is saved to the chosen location

**Verify the download:**
```bash
# Pull the downloaded file from the emulator
adb pull /sdcard/Download/master.kdbx /tmp/master-downloaded.kdbx

# Verify it can be opened with the correct password
keepassxc-cli ls /tmp/master-downloaded.kdbx <<< "testpassword123"
```

---

### TC-12: Download — No Master Exists

**Steps:**
1. Change the master name to `nonexistent-db`
2. Wait for the status to refresh (~300ms debounce + network)

**Expected:**
- The download section shows a **red/error card**: "No master database found for 'nonexistent-db'"
- The **Download** button is disabled/grayed out

---

### TC-13: Status — Auto-Refresh on Name Change

**Precondition:** Master DB `master` exists on the server.

**Steps:**
1. Set master name to `master` — observe "Last updated" appears
2. Change master name to `doesnotexist` — observe "No master database found" appears
3. Change master name back to `master` — observe "Last updated" reappears

**Expected:**
- Each name change triggers a status refresh after a brief debounce delay (~300ms)
- The UI updates smoothly between the "last updated" and "no database" states

---

### TC-14: Change Server

**Steps:**
1. On the Sync Screen, tap the **back arrow** (←) in the top bar
2. The Server Setup screen appears with the previously entered URL pre-filled
3. Change the URL to something else or keep the same
4. Tap **Connect**

**Expected:**
- The app navigates back to the Sync Screen
- The server URL chip updates to reflect any URL change
- Status refreshes against the new server

---

### TC-15: Dark Mode

**Steps:**
1. Open emulator settings: **Settings → Display → Dark theme** → Enable
2. Return to the KeePass Sync app

**Expected:**
- The app switches to a dark color scheme
- Background becomes dark (~#121212)
- Primary accent color shifts to a lighter green
- All text remains readable
- Cards, buttons, and status messages use appropriate dark-mode colors
- The error container for "no database found" is still distinguishable

---

### TC-16: Keyboard & Scroll Behavior

**Steps:**
1. On the Sync Screen, tap the **Master Password** field
2. The software keyboard appears
3. Scroll up and down while the keyboard is visible

**Expected:**
- The screen content scrolls under the keyboard
- All form fields remain accessible while the keyboard is open
- The Sync and Download cards are reachable by scrolling
- No content is permanently hidden behind the keyboard

---

## 9. Cleanup

After testing is complete:

```bash
# Stop the backend server
cd /home/yaakov/Keepass-Sync
docker compose down

# Remove test data
rm -rf test-data/
rm -f data/master.kdbx

# Uninstall the app from the emulator
adb uninstall com.keepasssync.app

# Stop and delete the emulator
adb emu kill
avdmanager delete avd --name test_device
```

---

## Quick Reference — Test Results Checklist

| ID | Test Case | Pass / Fail |
|----|-----------|-------------|
| TC-01 | Server Setup — Valid URL | ☐ |
| TC-02 | Server Setup — Invalid URL | ☐ |
| TC-03 | Server Setup — Unreachable Server | ☐ |
| TC-04 | Server Setup — URL Persistence | ☐ |
| TC-05 | Server Setup — Auto HTTP Prefix | ☐ |
| TC-06 | Sync Screen — Master Name Validation | ☐ |
| TC-07 | Sync — First Upload (Create Master) | ☐ |
| TC-08 | Sync — Merge Upload | ☐ |
| TC-09 | Sync — Wrong Password | ☐ |
| TC-10 | Sync — No File Selected | ☐ |
| TC-11 | Download — Successful Download | ☐ |
| TC-12 | Download — No Master Exists | ☐ |
| TC-13 | Status — Auto-Refresh on Name Change | ☐ |
| TC-14 | Change Server | ☐ |
| TC-15 | Dark Mode | ☐ |
| TC-16 | Keyboard & Scroll Behavior | ☐ |
