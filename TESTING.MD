# KeePass Sync — Testing Plan

Step-by-step instructions for building, running, and testing the Rust backend, the React frontend, and their integration — both locally and inside Docker.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Create Test Data](#2-create-test-data)
3. [Build & Run](#3-build--run)
   - [Local Development](#local-development)
   - [Docker (Production)](#docker-production)
4. [Rust Backend Unit Tests](#4-rust-backend-unit-tests)
5. [Backend API Tests (curl)](#5-backend-api-tests-curl)
   - [TC-01: Status — No Master Exists](#tc-01-status--no-master-exists)
   - [TC-02: Sync — Create Master (First Upload)](#tc-02-sync--create-master-first-upload)
   - [TC-03: Status — Master Exists](#tc-03-status--master-exists)
   - [TC-04: Sync — Merge Upload](#tc-04-sync--merge-upload)
   - [TC-05: Sync — Wrong Password](#tc-05-sync--wrong-password)
   - [TC-06: Sync — Missing File](#tc-06-sync--missing-file)
   - [TC-07: Sync — Missing Password](#tc-07-sync--missing-password)
   - [TC-08: Download — Success](#tc-08-download--success)
   - [TC-09: Download — Nonexistent Master](#tc-09-download--nonexistent-master)
   - [TC-10: Sync — Sanitized Master Name](#tc-10-sync--sanitized-master-name)
   - [TC-11: Sync — Force Merge (Unsupported Fields)](#tc-11-sync--force-merge-unsupported-fields)
   - [TC-12: Download — Invalid Master Name](#tc-12-download--invalid-master-name)
6. [Frontend UI Tests](#6-frontend-ui-tests)
   - [TC-13: Page Load](#tc-13-page-load)
   - [TC-14: Master Name Validation](#tc-14-master-name-validation)
   - [TC-15: Status Display — No Master](#tc-15-status-display--no-master)
   - [TC-16: Sync via UI — Create Master](#tc-16-sync-via-ui--create-master)
   - [TC-17: Status Display — After Sync](#tc-17-status-display--after-sync)
   - [TC-18: Sync via UI — Merge](#tc-18-sync-via-ui--merge)
   - [TC-19: Sync via UI — Wrong Password](#tc-19-sync-via-ui--wrong-password)
   - [TC-20: Download via UI](#tc-20-download-via-ui)
   - [TC-21: Multiple Master Names](#tc-21-multiple-master-names)
   - [TC-22: Form Reset After Sync](#tc-22-form-reset-after-sync)
   - [TC-23: Button Disabled States](#tc-23-button-disabled-states)
7. [Frontend ↔ Backend Integration Tests](#7-frontend--backend-integration-tests)
   - [TC-24: Vite Proxy Forwarding](#tc-24-vite-proxy-forwarding)
   - [TC-25: Static File Serving (Production)](#tc-25-static-file-serving-production)
   - [TC-26: SPA Fallback Routing](#tc-26-spa-fallback-routing)
8. [Docker & Persistence Tests](#8-docker--persistence-tests)
   - [TC-27: Container Rebuild Persistence](#tc-27-container-rebuild-persistence)
   - [TC-28: Data Volume Binding](#tc-28-data-volume-binding)
9. [Cleanup](#9-cleanup)
10. [Results Checklist](#10-results-checklist)

---

## 1. Prerequisites

| Tool | Purpose | Install |
|------|---------|---------|
| **Rust toolchain** (rustc, cargo) | Build & test the backend | [rustup.rs](https://rustup.rs/) |
| **Node.js 22+ & npm** | Build the Vite/React frontend | [nodejs.org](https://nodejs.org/) |
| **Docker + Docker Compose** | Build and run the containerized app | [docs.docker.com/engine/install](https://docs.docker.com/engine/install/) |
| **curl** | Backend API testing from the command line | Pre-installed on most Linux distros |
| **jq** | Pretty-print JSON responses | `sudo apt install jq` |
| **KeePassXC** (CLI) | Create & verify test `.kdbx` files | `sudo apt install keepassxc` |
| **Web browser** | Frontend UI testing | Any modern browser (Firefox, Chrome) |

> [!NOTE]
> The Rust backend uses the [`keepass`](https://crates.io/crates/keepass) crate (v0.13) with the `save_kdbx4` and `_merge` features, and [`axum`](https://crates.io/crates/axum) (v0.8) as the HTTP framework. Ensure your Rust toolchain is edition 2024-compatible (`rustup update stable`).

---

## 2. Create Test Data

Create two `.kdbx` test databases with different entries and a shared password. These will be used across API, UI, and integration tests.

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

## 3. Build & Run

### Local Development

Local dev runs Vite (port 5173) for the frontend with HMR, and `cargo run` (port 3000) for the Rust backend simultaneously. Vite proxies `/api` requests to the backend.

```bash
cd /home/yaakov/Keepass-Sync

# Install frontend dependencies
npm install

# Start both frontend dev server and Rust backend
npm run dev
```

**Expected:**
- Vite starts on `http://localhost:5173` (or next available port)
- Cargo compiles and starts the Axum server on `http://localhost:3000`
- Terminal output shows: `listening on 0.0.0.0:3000`
- Navigating to `http://localhost:5173` loads the React UI
- API calls from the UI are proxied to the backend via Vite's proxy config

> [!IMPORTANT]
> The Vite dev server proxies `/api` to `http://127.0.0.1:3000`. If the Rust backend fails to start, API calls will return 502 errors.

**Quick health check (against the backend directly):**
```bash
curl -s http://localhost:3000 | head -5
```

In dev mode this may return a 404 (no `dist/` built yet) — that's expected. The frontend is served by Vite on port 5173.

---

### Docker (Production)

The Dockerfile is a multi-stage build: Node.js builds the frontend (`vite build`), then the Rust binary (not yet containerized — see note) serves the built static files from `dist/`.

> [!WARNING]
> The current Dockerfile still references the old Node.js/TypeScript backend (`server.ts` via `npx tsx`). It will need to be updated to compile and run the Rust binary. Until then, use local development for testing. The tests below assume the backend is reachable at `http://localhost:3000` regardless of how it was started.

```bash
cd /home/yaakov/Keepass-Sync

# Clean any previous data
rm -rf data/

# Build and run
docker compose build
docker compose up -d

# Verify
docker compose ps
```

**Expected (once Dockerfile is updated):** Container `keepass-sync` is listed with status `Up` and port `0.0.0.0:3000->3000/tcp`.

```bash
# Quick health check
curl -s http://localhost:3000 | head -5
```

**Expected:** Returns HTML content (the `<!doctype html>` page).

---

## 4. Rust Backend Unit Tests

The Rust backend can be tested independently using `cargo test`. This exercises the KDBX merge logic and sanitization functions without needing a running server.

```bash
cd /home/yaakov/Keepass-Sync/backend

# Run all tests
cargo test

# Run with output visible
cargo test -- --nocapture
```

**Expected:** All tests pass. If no tests exist yet, `cargo test` should compile successfully with `0 tests passed`.

### Verifying the Backend Compiles

```bash
cd /home/yaakov/Keepass-Sync/backend
cargo build
```

**Expected:** Compilation succeeds without errors. Warnings about unused imports are acceptable but no hard errors.

### Running the Backend Standalone

```bash
cd /home/yaakov/Keepass-Sync/backend
cargo run
```

**Expected:**
- Console output: `listening on 0.0.0.0:3000`
- The server is ready to accept requests
- `Ctrl+C` stops the server cleanly

---

## 5. Backend API Tests (curl)

These tests exercise the three API endpoints (`/api/sync`, `/api/status`, `/api/download`) directly using `curl`. Run each test in sequence — later tests depend on state created by earlier ones.

> [!IMPORTANT]
> Ensure the Rust backend is running on `http://localhost:3000` before starting these tests. Start it with either `npm run dev` (which starts both frontend and backend) or `cargo run --manifest-path backend/Cargo.toml`.

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

Upload `test-db-A.kdbx` when no master exists yet. The server saves the uploaded file directly as the new master.

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
{ "lastUpdated": "2026-07-01T..." }
```
The `lastUpdated` field should be a recent RFC 3339 timestamp (not `null`).

---

### TC-04: Sync — Merge Upload

Upload `test-db-B.kdbx` to merge its entries into the existing master. The Rust backend uses the `keepass` crate's native merge logic.

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
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=wrongpassword" \
  -F "masterName=master"
```

**Expected HTTP status:** `500`

**View response body:**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=wrongpassword" \
  -F "masterName=master" | jq .
```

**Expected response:**
```json
{
  "error": "Internal server error during sync",
  "details": "Failed to decrypt uploaded db: ..."
}
```

> [!NOTE]
> The Rust backend returns HTTP 500 with a generic error message and a `details` field containing the decrypt error. The exact text in `details` varies by `keepass` crate version but will reference decryption failure.

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
{ "error": "File required" }
```

**Verify status code:**
```bash
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:3000/api/sync \
  -F "password=testpassword123" \
  -F "masterName=master"
```
**Expected:** `400`

---

### TC-07: Sync — Missing Password

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "masterName=master" | jq .
```

**Expected response (HTTP 400):**
```json
{ "error": "Password required" }
```

**Verify status code:**
```bash
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "masterName=master"
```
**Expected:** `400`

---

### TC-08: Download — Success

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

**Verify Content-Disposition header:**
```bash
curl -s -D - -X POST http://localhost:3000/api/download \
  -H "Content-Type: application/json" \
  -d '{"masterName":"master"}' -o /dev/null 2>&1 | grep -i content-disposition
```
**Expected:** `content-disposition: attachment; filename="master.kdbx"`

---

### TC-09: Download — Nonexistent Master

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

### TC-10: Sync — Sanitized Master Name

Verify the server strips special characters from master names. The Rust backend allows only alphanumeric characters, hyphens (`-`), and underscores (`_`).

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

**Verify path traversal is blocked:**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=testpassword123" \
  -F "masterName=../../../etc/passwd" | jq .
```

**Verify no path traversal occurred:**
```bash
# The sanitized name should be "etcpasswd" (dots, slashes stripped; only alphanumeric kept)
ls data/etcpasswd.kdbx
```
**Expected:** File `data/etcpasswd.kdbx` exists (sanitized). No files created outside `data/`.

**Test with underscores (allowed by Rust backend):**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=testpassword123" \
  -F "masterName=my_backup_db" | jq .
```

**Expected:**
```json
{ "message": "Master database created successfully" }
```

```bash
ls data/my_backup_db.kdbx
```
**Expected:** File exists (underscores are preserved).

---

### TC-11: Sync — Force Merge (Unsupported Fields)

When a database contains unsupported `custom_data` fields, the backend returns HTTP 409 unless `force=true` is set.

> [!NOTE]
> This test requires a `.kdbx` file that has custom data fields. If your test databases were created with standard KeePassXC, they may not trigger this code path. The test below verifies the force flag is accepted by the API.

**Command (force flag):**
```bash
curl -s -X POST http://localhost:3000/api/sync \
  -F "dbFile=@test-data/test-db-A.kdbx" \
  -F "password=testpassword123" \
  -F "masterName=force-test" \
  -F "force=true" | jq .
```

**Expected response (success, since force skips unsupported-field check):**
```json
{ "message": "Master database created successfully" }
```

---

### TC-12: Download — Invalid Master Name

**Command:**
```bash
curl -s -X POST http://localhost:3000/api/download \
  -H "Content-Type: application/json" \
  -d '{"masterName":""}' | jq .
```

**Expected response (HTTP 400):**
```json
{ "error": "Invalid master name" }
```

**Verify status code:**
```bash
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:3000/api/download \
  -H "Content-Type: application/json" \
  -d '{"masterName":""}'
```
**Expected:** `400`

---

## 6. Frontend UI Tests

Open a browser and navigate to the frontend. Use **`http://localhost:5173`** for local dev, or **`http://localhost:3000`** for Docker/production. These tests should be performed manually in the browser.

> [!IMPORTANT]
> Before starting UI tests, reset the server state for a clean run:
> ```bash
> rm -f data/*.kdbx
> ```

---

### TC-13: Page Load

**Steps:**
1. Navigate to the frontend URL

**Expected:**
- Page title reads "KeePass Sync"
- Subtitle: "Synchronize your .kdbx files securely"
- Two cards visible: **"Upload & Sync"** and **"Download Master"**
- Master Database Name field defaults to `master`
- File input, password field, and "Sync Database" button are visible
- No errors or console warnings (open DevTools → Console to verify)

---

### TC-14: Master Name Validation

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

### TC-15: Status Display — No Master

**Steps:**
1. Ensure master name is `master` (and no master DB file exists on the server)
2. Look at the **Download Master** card

**Expected:**
- Shows a dashed-border info box: "No master database found for 'master'."
- The **Download 'master' DB** button is disabled (grayed out)

---

### TC-16: Sync via UI — Create Master

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

### TC-17: Status Display — After Sync

**Steps:**
1. Observe the Download Master card after TC-16

**Expected:**
- A gray info box shows: "Last updated: **\<recent datetime\>**"
- The displayed time matches the current time (within a few seconds)

---

### TC-18: Sync via UI — Merge

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

### TC-19: Sync via UI — Wrong Password

**Steps:**
1. Click **Choose File** → select `test-data/test-db-A.kdbx`
2. Enter password: `badpassword`
3. Click **Sync Database**

**Expected:**
- A **red error banner** appears with an error message about sync failure
- The file selection and password field are **not** cleared (user can correct and retry)

> [!NOTE]
> The Rust backend returns `"Internal server error during sync"` as the error message. The frontend displays whichever error string the API returns.

---

### TC-20: Download via UI

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

### TC-21: Multiple Master Names

**Steps:**
1. Change master name to `work`
2. Observe the Download card updates to "No master database found for 'work'."
3. Upload `test-data/test-db-A.kdbx` with password `testpassword123`
4. Verify success message: "Master database created successfully"
5. Change master name back to `master`
6. Verify "Last updated" still shows for `master`

**Expected:**
- Each master name operates independently
- Switching names triggers a status refresh (after a 300ms debounce)

**Verify both files exist:**
```bash
ls -la data/master.kdbx data/work.kdbx
```

---

### TC-22: Form Reset After Sync

**Steps:**
1. Upload any `.kdbx` file and sync successfully
2. Observe the form state

**Expected:**
- The file input is cleared (shows no file selected)
- The password field is cleared
- The master name field retains its value

---

### TC-23: Button Disabled States

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

## 7. Frontend ↔ Backend Integration Tests

These tests verify that the frontend and backend communicate correctly, both through Vite's dev proxy and in production mode.

---

### TC-24: Vite Proxy Forwarding

**Prerequisite:** Running in local dev mode (`npm run dev`).

**Steps:**
1. Open browser DevTools → Network tab
2. Navigate to `http://localhost:5173`
3. Observe the status request that fires on page load

**Expected:**
- A POST request to `/api/status` appears in the network tab
- The request is sent to `localhost:5173` (Vite) and proxied to `localhost:3000` (Rust backend)
- Response status is `200` with valid JSON (`{ "lastUpdated": ... }`)
- No CORS errors in the console

**Verify proxy config in `vite.config.ts`:**
```bash
grep -A2 "proxy" vite.config.ts
```
**Expected:** Shows `'/api': 'http://127.0.0.1:3000'`

---

### TC-25: Static File Serving (Production)

**Prerequisite:** Frontend is built and backend is serving from `dist/`.

**Steps:**
```bash
# Build the frontend
npm run build

# Verify dist/ was created
ls dist/index.html

# Start the backend (it serves dist/ as a fallback)
cargo run --manifest-path backend/Cargo.toml &
BACKEND_PID=$!
sleep 2

# Fetch the page from the backend directly
curl -s http://localhost:3000 | head -5

# Cleanup
kill $BACKEND_PID
```

**Expected:**
- `dist/index.html` exists after `npm run build`
- `curl http://localhost:3000` returns the HTML page (the Axum `ServeDir` fallback serves the built frontend)
- Static assets (JS, CSS) are served correctly

---

### TC-26: SPA Fallback Routing

**Prerequisite:** Same as TC-25 (production mode with `dist/` built).

**Steps:**
```bash
# Request a non-existent path (SPA client-side route)
curl -s http://localhost:3000/nonexistent-page | head -5
```

**Expected:** Returns the `index.html` content (SPA fallback via `ServeFile::new("dist/index.html")`), not a 404.

---

## 8. Docker & Persistence Tests

> [!WARNING]
> These tests require an updated Dockerfile that builds and runs the Rust backend. If the Dockerfile still references the old Node.js server, update it first (compile the Rust binary in a builder stage, then copy it into a minimal runtime image).

---

### TC-27: Container Rebuild Persistence

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
sleep 3

# Check the master DB is still accessible
curl -s -X POST http://localhost:3000/api/status \
  -H "Content-Type: application/json" \
  -d '{"masterName":"master"}' | jq .
```

**Expected:** `lastUpdated` is a valid timestamp (not `null`). Data persisted across container restart.

---

### TC-28: Data Volume Binding

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

## 9. Cleanup

```bash
cd /home/yaakov/Keepass-Sync

# Stop Docker container (if running)
docker compose down 2>/dev/null

# Remove test data and server data
rm -rf test-data/
rm -rf data/

# Remove downloaded test files
rm -f /tmp/downloaded-master.kdbx

# Clean Rust build artifacts (optional)
cd backend && cargo clean && cd ..
```

---

## 10. Results Checklist

### Rust Backend (Build & Unit)

| ID | Test Case | Pass / Fail |
|----|-----------|-------------|
| — | Backend compiles (`cargo build`) | ☐ |
| — | Unit tests pass (`cargo test`) | ☐ |
| — | Backend starts (`cargo run`) | ☐ |

### Backend API

| ID | Test Case | Pass / Fail |
|----|-----------|-------------|
| TC-01 | Status — No Master Exists | ☐ |
| TC-02 | Sync — Create Master (First Upload) | ☐ |
| TC-03 | Status — Master Exists | ☐ |
| TC-04 | Sync — Merge Upload | ☐ |
| TC-05 | Sync — Wrong Password | ☐ |
| TC-06 | Sync — Missing File | ☐ |
| TC-07 | Sync — Missing Password | ☐ |
| TC-08 | Download — Success | ☐ |
| TC-09 | Download — Nonexistent Master | ☐ |
| TC-10 | Sync — Sanitized Master Name | ☐ |
| TC-11 | Sync — Force Merge | ☐ |
| TC-12 | Download — Invalid Master Name | ☐ |

### Frontend UI

| ID | Test Case | Pass / Fail |
|----|-----------|-------------|
| TC-13 | Page Load | ☐ |
| TC-14 | Master Name Validation | ☐ |
| TC-15 | Status Display — No Master | ☐ |
| TC-16 | Sync via UI — Create Master | ☐ |
| TC-17 | Status Display — After Sync | ☐ |
| TC-18 | Sync via UI — Merge | ☐ |
| TC-19 | Sync via UI — Wrong Password | ☐ |
| TC-20 | Download via UI | ☐ |
| TC-21 | Multiple Master Names | ☐ |
| TC-22 | Form Reset After Sync | ☐ |
| TC-23 | Button Disabled States | ☐ |

### Frontend ↔ Backend Integration

| ID | Test Case | Pass / Fail |
|----|-----------|-------------|
| TC-24 | Vite Proxy Forwarding | ☐ |
| TC-25 | Static File Serving (Production) | ☐ |
| TC-26 | SPA Fallback Routing | ☐ |

### Docker & Persistence

| ID | Test Case | Pass / Fail |
|----|-----------|-------------|
| TC-27 | Container Rebuild Persistence | ☐ |
| TC-28 | Data Volume Binding | ☐ |
