# KeePass Sync — Web App Testing Plan

Step-by-step instructions for building, deploying, and testing the web UI and backend API on a Linux machine using Docker.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Create Test Data](#2-create-test-data)
3. [Build & Start the Docker Container](#3-build--start-the-docker-container)
4. [Backend API Tests](#4-backend-api-tests)
   - [TC-01: Status — No Master Exists](#tc-01-status--no-master-exists)
   - [TC-02: Sync — Create Master (First Upload)](#tc-02-sync--create-master-first-upload)
   - [TC-03: Status — Master Exists](#tc-03-status--master-exists)
   - [TC-04: Sync — Merge Upload](#tc-04-sync--merge-upload)
   - [TC-05: Sync — Wrong Password](#tc-05-sync--wrong-password)
   - [TC-06: Sync — Missing File](#tc-06-sync--missing-file)
   - [TC-07: Download — Success](#tc-07-download--success)
   - [TC-08: Download — Nonexistent Master](#tc-08-download--nonexistent-master)
   - [TC-09: Sync — Sanitized Master Name](#tc-09-sync--sanitized-master-name)
5. [Frontend UI Tests](#5-frontend-ui-tests)
   - [TC-10: Page Load](#tc-10-page-load)
   - [TC-11: Master Name Validation](#tc-11-master-name-validation)
   - [TC-12: Status Display — No Master](#tc-12-status-display--no-master)
   - [TC-13: Sync via UI — Create Master](#tc-13-sync-via-ui--create-master)
   - [TC-14: Status Display — After Sync](#tc-14-status-display--after-sync)
   - [TC-15: Sync via UI — Merge](#tc-15-sync-via-ui--merge)
   - [TC-16: Sync via UI — Wrong Password](#tc-16-sync-via-ui--wrong-password)
   - [TC-17: Download via UI](#tc-17-download-via-ui)
   - [TC-18: Multiple Master Names](#tc-18-multiple-master-names)
   - [TC-19: Form Reset After Sync](#tc-19-form-reset-after-sync)
   - [TC-20: Button Disabled States](#tc-20-button-disabled-states)
6. [Docker & Persistence Tests](#6-docker--persistence-tests)
   - [TC-21: Container Rebuild Persistence](#tc-21-container-rebuild-persistence)
   - [TC-22: Data Volume Binding](#tc-22-data-volume-binding)
7. [Cleanup](#7-cleanup)
8. [Results Checklist](#8-results-checklist)

---

## 1. Prerequisites

| Tool | Purpose | Install |
|------|---------|---------|
| **Docker + Docker Compose** | Build and run the containerized app | [docs.docker.com/engine/install](https://docs.docker.com/engine/install/) |
| **curl** | Backend API testing from the command line | Pre-installed on most Linux distros |
| **jq** | Pretty-print JSON responses | `sudo apt install jq` |
| **KeePassXC** (CLI) | Create test `.kdbx` files | `sudo apt install keepassxc` |
| **Web browser** | Frontend UI testing | Any modern browser (Firefox, Chrome) |

---

## 2. Create Test Data

Create two `.kdbx` test databases with different entries and a shared password. These will be used for both API and UI tests.

```bash
cd /home/yaakov/Keepass-Sync
mkdir -p test-data

# Create database A with one entry
keepassxc-cli db-create test-data/test-db-A.kdbx -p <<< "testpassword123"
keepassxc-cli add test-data/test-db-A.kdbx -u "user_A" --url "https://site-a.com" "EntryFromA" -p <<< "testpassword123"

# Create database B (clone of A with an additional entry)
cp test-data/test-db-A.kdbx test-data/test-db-B.kdbx
keepassxc-cli add test-data/test-db-B.kdbx -u "user_B" --url "https://site-b.com" "EntryFromB" -p <<< "testpassword123"
```

**Verify the test data:**

```bash
echo "--- DB A entries ---"
keepassxc-cli ls test-data/test-db-A.kdbx <<< "testpassword123"

echo "--- DB B entries ---"
keepassxc-cli ls test-data/test-db-B.kdbx <<< "testpassword123"
```

**Expected:**
- DB A contains `EntryFromA`
- DB B contains `EntryFromA` and `EntryFromB`

> [!NOTE]
> If `keepassxc-cli` is unavailable, create the databases manually using the KeePassXC GUI. Use password `testpassword123` for both.

---

## 3. Build & Start the Docker Container

```bash
cd /home/yaakov/Keepass-Sync

# Clean any previous data
rm -rf data/

# Build the Docker image
docker compose build

# Start the container in detached mode
docker compose up -d

# Verify the container is running
docker compose ps
```

**Expected:** Container `keepass-sync` is listed with status `Up` and port `0.0.0.0:3000->3000/tcp`.

```bash
# Quick health check
curl -s http://localhost:3000 | head -5
```

**Expected:** Returns HTML content (the `<!doctype html>` page).

---

## 4. Backend API Tests

These tests exercise the three API endpoints directly using `curl`. Run each test in sequence — later tests depend on state created by earlier ones.

---

### TC-01: Status — No Master Exists

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/status \
  -H "Content-Type: application/json" \
  -d '{"masterName":"master"}' | jq .
```

**Expected response:**
```json
{ "lastUpdated": null }
```

---

### TC-02: Sync — Create Master (First Upload)

Upload `test-db-A.kdbx` when no master exists yet. The server should save it as the new master.

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=testpassword123" \
  -F "masterName=master" | jq .
```

**Expected response:**
```json
{ "message": "Master database created successfully" }
```

**Verify on host filesystem:**
```bash
ls -la data/master.kdbx
```
**Expected:** File exists with a non-zero size.

---

### TC-03: Status — Master Exists

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/status \
  -H "Content-Type: application/json" \
  -d '{"masterName":"master"}' | jq .
```

**Expected response:**
```json
{ "lastUpdated": "2026-04-16T..." }
```
The `lastUpdated` field should be a recent ISO 8601 timestamp (not `null`).

---

### TC-04: Sync — Merge Upload

Upload `test-db-B.kdbx` to merge its entries into the existing master.

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-B.kdbx" \
  -F "password=testpassword123" \
  -F "masterName=master" | jq .
```

**Expected response:**
```json
{ "message": "Database merged successfully" }
```

**Verify merge contains entries from both databases:**
```bash
keepassxc-cli ls data/master.kdbx <<< "testpassword123"
```
**Expected:** Output lists both `EntryFromA` and `EntryFromB`.

---

### TC-05: Sync — Wrong Password

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=wrongpassword" \
  -F "masterName=master" | jq .
```

**Expected response (HTTP 401):**
```json
{ "error": "Failed to decrypt uploaded database. Incorrect password?" }
```

**Verify with status code:**
```bash
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=wrongpassword" \
  -F "masterName=master"
```
**Expected:** `401`

---

### TC-06: Sync — Missing File

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "password=testpassword123" \
  -F "masterName=master" | jq .
```

**Expected response (HTTP 400):**
```json
{ "error": "File and password are required" }
```

---

### TC-07: Download — Success

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/download \
  -H "Content-Type: application/json" \
  -d '{"masterName":"master"}' \
  -o /tmp/downloaded-master.kdbx

ls -la /tmp/downloaded-master.kdbx
```

**Expected:** File downloads successfully with a non-zero size.

**Verify the downloaded file is valid:**
```bash
keepassxc-cli ls /tmp/downloaded-master.kdbx <<< "testpassword123"
```
**Expected:** Lists entries including `EntryFromA` and `EntryFromB`.

---

### TC-08: Download — Nonexistent Master

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/download \
  -H "Content-Type: application/json" \
  -d '{"masterName":"doesnotexist"}' | jq .
```

**Expected response (HTTP 404):**
```json
{ "error": "Master database not found" }
```

**Verify status code:**
```bash
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:3000/api/download \
  -H "Content-Type: application/json" \
  -d '{"masterName":"doesnotexist"}'
```
**Expected:** `404`

---

### TC-09: Sync — Sanitized Master Name

Verify the server strips special characters from master names.

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=testpassword123" \
  -F "masterName=work-db" | jq .
```

**Expected response:**
```json
{ "message": "Master database created successfully" }
```

**Verify file is created with the sanitized name:**
```bash
ls data/work-db.kdbx
```
**Expected:** File exists.

**Verify with a name containing illegal characters:**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=testpassword123" \
  -F "masterName=../../../etc/passwd" | jq .
```

**Verify no path traversal occurred:**
```bash
# The sanitized name should be "etcpasswd" (all special chars stripped)
ls data/etcpasswd.kdbx
```
**Expected:** File `data/etcpasswd.kdbx` exists (sanitized). No files created outside `data/`.

---

## 5. Frontend UI Tests

Open a browser and navigate to **http://localhost:3000**. These tests should be performed manually in the browser.

> [!IMPORTANT]
> Before starting UI tests, reset the server state for a clean run:
> ```bash
> rm -f data/master.kdbx data/work-db.kdbx data/etcpasswd.kdbx
> ```

---

### TC-10: Page Load

**Steps:**
1. Navigate to `http://localhost:3000`

**Expected:**
- Page title reads "KeePass Sync"
- Subtitle: "Synchronize your .kdbx files securely"
- Two cards visible: **"Upload & Sync"** and **"Download Master"**
- Master Database Name field defaults to `master`
- File input, password field, and "Sync Database" button are visible
- No errors or console warnings

---

### TC-11: Master Name Validation

**Steps:**
1. Clear the Master Database Name field
2. Type `hello world!` (contains space and `!`)

**Expected:**
- The input field shows a red border
- Red helper text: "Only alphanumeric characters and hyphens are allowed."

**Steps (continued):**
3. Clear and type `my-personal-db`

**Expected:**
- Red border disappears
- No error text

---

### TC-12: Status Display — No Master

**Steps:**
1. Ensure master name is `master` (and no master DB file exists on the server)
2. Look at the **Download Master** card

**Expected:**
- Shows a dashed-border info box: "No master database found for 'master'."
- The **Download 'master' DB** button is disabled (grayed out)

---

### TC-13: Sync via UI — Create Master

**Steps:**
1. Set master name to `master`
2. Click **Choose File** → select `test-data/test-db-A.kdbx`
3. Enter password: `testpassword123`
4. Click **Sync Database**

**Expected:**
- Button changes to a spinner with "Syncing..."
- After a moment, a **green success banner** appears: "Master database created successfully"
- The Download card updates to show a **"Last updated: ..."** timestamp
- The **Download** button becomes enabled

---

### TC-14: Status Display — After Sync

**Steps:**
1. Observe the Download Master card after TC-13

**Expected:**
- A gray info box shows: "Last updated: **\<recent datetime\>**"
- The displayed time matches the current time (within a few seconds)

---

### TC-15: Sync via UI — Merge

**Steps:**
1. Click **Choose File** → select `test-data/test-db-B.kdbx`
2. Enter password: `testpassword123`
3. Click **Sync Database**

**Expected:**
- Green success banner: "Database merged successfully"
- "Last updated" timestamp refreshes to a newer time

**Verify on server:**
```bash
keepassxc-cli ls data/master.kdbx <<< "testpassword123"
```
**Expected:** Both `EntryFromA` and `EntryFromB` are listed.

---

### TC-16: Sync via UI — Wrong Password

**Steps:**
1. Click **Choose File** → select `test-data/test-db-A.kdbx`
2. Enter password: `badpassword`
3. Click **Sync Database**

**Expected:**
- A **red error banner** appears: "Failed to decrypt uploaded database. Incorrect password?"
- The file selection and password field are **not** cleared (user can correct and retry)

---

### TC-17: Download via UI

**Steps:**
1. Ensure the Download card shows "Last updated: ..."
2. Click **Download 'master' DB**

**Expected:**
- The browser downloads a file named `master.kdbx`
- The downloaded file size is non-zero

**Verify the download:**
```bash
keepassxc-cli ls ~/Downloads/master.kdbx <<< "testpassword123"
```
**Expected:** Entries are listed successfully, confirming a valid `.kdbx` file.

---

### TC-18: Multiple Master Names

**Steps:**
1. Change master name to `work`
2. Observe the Download card updates to "No master database found for 'work'."
3. Upload `test-db-A.kdbx` with password `testpassword123`
4. Verify success message: "Master database created successfully"
5. Change master name back to `master`
6. Verify "Last updated" still shows for `master`

**Expected:**
- Each master name operates independently
- Switching names triggers a status refresh

**Verify both files exist:**
```bash
ls -la data/master.kdbx data/work.kdbx
```

---

### TC-19: Form Reset After Sync

**Steps:**
1. Upload any `.kdbx` file and sync successfully
2. Observe the form state

**Expected:**
- The file input is cleared (shows no file selected)
- The password field is cleared
- The master name field retains its value

---

### TC-20: Button Disabled States

Test that the Sync button is disabled when required fields are missing.

| Master Name | File Selected | Password | Expected Button State |
|-------------|---------------|----------|-----------------------|
| `master` | ✅ | ✅ | **Enabled** |
| *(empty)* | ✅ | ✅ | Disabled |
| `master` | ❌ | ✅ | Disabled |
| `master` | ✅ | *(empty)* | Disabled |
| `master` | ❌ | *(empty)* | Disabled |

**Steps:**
1. Clear all fields, verify button is disabled
2. Fill in master name only → still disabled
3. Select a file → still disabled
4. Enter a password → button becomes **enabled**
5. Clear the password → button goes back to disabled

---

## 6. Docker & Persistence Tests

### TC-21: Container Rebuild Persistence

Verify that master databases survive a container restart because they're stored on a bind-mounted volume.

**Steps:**
```bash
# Confirm a master DB exists
ls -la data/master.kdbx

# Stop and remove the container
docker compose down

# Rebuild and restart
docker compose up -d --build

# Wait for startup
sleep 2

# Check the master DB is still accessible
curl -s -X POST http://localhost:3000/api/status \
  -H "Content-Type: application/json" \
  -d '{"masterName":"master"}' | jq .
```

**Expected:** `lastUpdated` is a valid timestamp (not `null`). Data persisted across container restart.

---

### TC-22: Data Volume Binding

Verify the host `./data` directory is the source of truth.

**Steps:**
```bash
# Create a new master via API
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=testpassword123" \
  -F "masterName=volume-test" | jq .

# Verify file appears on the host filesystem
ls -la data/volume-test.kdbx

# Delete it from the host
rm data/volume-test.kdbx

# Verify the server no longer reports it
curl -s -X POST http://localhost:3000/api/status \
  -H "Content-Type: application/json" \
  -d '{"masterName":"volume-test"}' | jq .
```

**Expected:**
- File appears on host after sync
- After deletion on host, status returns `{ "lastUpdated": null }`
- Confirms the container is reading/writing directly to the bind mount

---

## 7. Cleanup

```bash
cd /home/yaakov/Keepass-Sync

# Stop and remove the container
docker compose down

# Remove test data and server data
rm -rf test-data/
rm -rf data/

# Remove downloaded test files
rm -f /tmp/downloaded-master.kdbx
```

---

## 8. Results Checklist

### Backend API

| ID | Test Case | Pass / Fail |
|----|-----------|-------------|
| TC-01 | Status — No Master Exists | ☐ |
| TC-02 | Sync — Create Master (First Upload) | ☐ |
| TC-03 | Status — Master Exists | ☐ |
| TC-04 | Sync — Merge Upload | ☐ |
| TC-05 | Sync — Wrong Password | ☐ |
| TC-06 | Sync — Missing File | ☐ |
| TC-07 | Download — Success | ☐ |
| TC-08 | Download — Nonexistent Master | ☐ |
| TC-09 | Sync — Sanitized Master Name | ☐ |

### Frontend UI

| ID | Test Case | Pass / Fail |
|----|-----------|-------------|
| TC-10 | Page Load | ☐ |
| TC-11 | Master Name Validation | ☐ |
| TC-12 | Status Display — No Master | ☐ |
| TC-13 | Sync via UI — Create Master | ☐ |
| TC-14 | Status Display — After Sync | ☐ |
| TC-15 | Sync via UI — Merge | ☐ |
| TC-16 | Sync via UI — Wrong Password | ☐ |
| TC-17 | Download via UI | ☐ |
| TC-18 | Multiple Master Names | ☐ |
| TC-19 | Form Reset After Sync | ☐ |
| TC-20 | Button Disabled States | ☐ |

### Docker & Persistence

| ID | Test Case | Pass / Fail |
|----|-----------|-------------|
| TC-21 | Container Rebuild Persistence | ☐ |
| TC-22 | Data Volume Binding | ☐ |
