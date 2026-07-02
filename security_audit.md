# KeePass-Sync Security Audit

> [!CAUTION]
> This application handles **password manager databases** — arguably the most sensitive user data possible. Security issues here are especially high-impact because a compromised `.kdbx` file grants access to *all* of a user's credentials.

---

## 🔴 Critical Issues

### 1. No Authentication on Any API Endpoint
**Files:** [main.rs](file:///home/yaakov/Keepass-Sync/backend/src/main.rs#L24-L35), [api.rs](file:///home/yaakov/Keepass-Sync/backend/src/api.rs)

All three API endpoints (`/api/sync`, `/api/download`, `/api/status`) are completely unauthenticated. **Anyone who can reach the server can:**
- **Download any user's encrypted KeePass database** via `/api/download` — only a `masterName` is required
- **Overwrite any user's database** via `/api/sync` by uploading a new file
- **Enumerate which databases exist** via `/api/status`

While the `.kdbx` files are encrypted at rest, an attacker who downloads one can perform offline brute-force attacks against the master password indefinitely.

> **Recommendation:** Add authentication (e.g., API key, bearer token, or mutual TLS). At minimum, require the database's master password to be verified before allowing downloads.

---

### 2. No Rate Limiting — Enables Online Brute-Force
**Files:** [main.rs](file:///home/yaakov/Keepass-Sync/backend/src/main.rs), [api.rs](file:///home/yaakov/Keepass-Sync/backend/src/api.rs#L27-L72)

The `/api/sync` endpoint accepts a `password` and decrypts the uploaded database. There is no rate limiting anywhere, meaning an attacker can repeatedly call `/api/sync` with different passwords to probe whether a given password is correct (the server returns different errors for wrong-password vs. success).

> **Recommendation:** Add rate limiting per IP (e.g., `tower::limit::RateLimitLayer` or a middleware like `tower-governor`). Consider adding account lockout after N failed attempts.

---

### 3. No TLS — Passwords Sent in Cleartext
**Files:** [main.rs](file:///home/yaakov/Keepass-Sync/backend/src/main.rs#L38), [SyncApiClient.kt](file:///home/yaakov/Keepass-Sync/android-app/app/src/main/java/com/keepasssync/app/network/SyncApiClient.kt), [AndroidManifest.xml](file:///home/yaakov/Keepass-Sync/android-app/app/src/main/AndroidManifest.xml#L18)

The backend listens on plain HTTP (`0.0.0.0:3000`). Master passwords are transmitted in cleartext over the network:
- In the web frontend, they are sent as a `FormData` field
- In the Android app, `usesCleartextTraffic="true"` is explicitly enabled, and the default URL suggestion is `http://`

Any attacker on the same network (e.g., WiFi) can sniff the master password.

> **Recommendation:** Terminate TLS at the server (or use a reverse proxy like nginx/caddy). Restrict `usesCleartextTraffic` to debug builds only in Android.

---

## 🟠 High-Severity Issues

### 4. GEMINI_API_KEY Leaked to Frontend Bundle
**File:** [vite.config.ts](file:///home/yaakov/Keepass-Sync/vite.config.ts#L11)

```typescript
define: {
  'process.env.GEMINI_API_KEY': JSON.stringify(env.GEMINI_API_KEY),
},
```

This injects the `GEMINI_API_KEY` directly into the compiled JavaScript bundle, making it visible to anyone who views the page source. While this key doesn't appear to be used in the current app code, it's still compiled into the output and can be extracted.

> **Recommendation:** Remove this `define` entry. API keys should never be embedded in client-side code.

---

### 5. Error Details Leaked to Clients
**File:** [api.rs](file:///home/yaakov/Keepass-Sync/backend/src/api.rs#L68-L71)

```rust
Err(e) => {
    tracing::error!("[Secure] Sync error occurred...");
    (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({
        "error": "Internal server error during sync", "details": e
    }))).into_response()
}
```

While the log message is redacted, the raw error string `e` (which may contain file paths, password-related decryption errors, or internal state) is returned directly to the client in the `details` field. This can aid attackers in understanding the system internals.

> **Recommendation:** Remove the `"details": e` from the response. Log it server-side only.

---

### 6. No Upload Size Limit — Denial of Service
**Files:** [api.rs](file:///home/yaakov/Keepass-Sync/backend/src/api.rs#L27-L44), [Cargo.toml](file:///home/yaakov/Keepass-Sync/backend/Cargo.toml)

The `sync_handler` accepts multipart uploads with no configured body size limit. Axum's default multipart limit may be large (or unbounded in some versions). An attacker can upload a multi-gigabyte file to exhaust server memory or disk.

> **Recommendation:** Configure `axum::extract::Multipart` with an explicit `ContentLengthLimit` or use `DefaultBodyLimit::max()` layer. A reasonable limit for `.kdbx` files is ~50MB.

---

### 7. Docker Container Runs as Root
**File:** [Dockerfile](file:///home/yaakov/Keepass-Sync/Dockerfile#L21-L33)

The runtime stage doesn't create or switch to a non-root user. The binary runs as `root` inside the container, meaning any RCE vulnerability gives full container control.

```dockerfile
# ---- Stage 3: Runtime ----
FROM alpine:3.20
WORKDIR /app
# No USER directive — runs as root
```

> **Recommendation:** Add a non-root user:
> ```dockerfile
> RUN adduser -D -u 1001 appuser && chown -R appuser:appuser /app
> USER appuser
> ```

---

## 🟡 Medium-Severity Issues

### 8. Download Endpoint Doesn't Require Password Verification
**File:** [api.rs](file:///home/yaakov/Keepass-Sync/backend/src/api.rs#L75-L96)

The `/api/download` endpoint returns the raw `.kdbx` file without requiring the master password. Combined with Issue #1, anyone who knows (or guesses) the `masterName` can download the encrypted database for offline cracking.

> **Recommendation:** Require the master password in the download request and verify it can decrypt the database before serving the file.

---

### 9. Database Name Enumeration via `/api/status`
**File:** [api.rs](file:///home/yaakov/Keepass-Sync/backend/src/api.rs#L98-L113)

The `/api/status` endpoint reveals whether a given `masterName` exists on the server by returning a `lastUpdated` timestamp vs. `null`. An attacker can enumerate all database names by brute-forcing this endpoint.

> **Recommendation:** Require authentication before revealing database existence. Alternatively, return the same response regardless of whether the database exists.

---

### 10. `android:allowBackup="true"` in Android App
**File:** [AndroidManifest.xml](file:///home/yaakov/Keepass-Sync/android-app/app/src/main/AndroidManifest.xml#L12)

With `allowBackup="true"`, the app's data (which may include cached server URLs, preferences, or other sensitive data) can be extracted via `adb backup` on devices with USB debugging enabled.

> **Recommendation:** Set `android:allowBackup="false"` or use `android:dataExtractionRules` to restrict what is backed up.

---

### 11. No Security Headers on HTTP Responses
**File:** [main.rs](file:///home/yaakov/Keepass-Sync/backend/src/main.rs)

The server doesn't set any security headers:
- No `Content-Security-Policy` → XSS risk
- No `X-Content-Type-Options: nosniff`
- No `Strict-Transport-Security` (HSTS)
- No `X-Frame-Options` → clickjacking risk

> **Recommendation:** Add security headers middleware. `tower-http` has a `SetResponseHeader` layer, or use a dedicated crate.

---

## 🔵 Low-Severity / Informational

### 12. Hardcoded Test Password in Version Control
**Files:** [make_db.py](file:///home/yaakov/Keepass-Sync/make_db.py#L5), [make-db.exp](file:///home/yaakov/Keepass-Sync/make-db.exp#L4), [test_sync.ts](file:///home/yaakov/Keepass-Sync/test_sync.ts#L21), [test_kdbx.ts](file:///home/yaakov/Keepass-Sync/test_kdbx.ts#L23), [test_args.ts](file:///home/yaakov/Keepass-Sync/test_args.ts#L32)

The password `testpassword123` is hardcoded in multiple test/utility files. While these are test utilities, if the same password is used for the production `data/master.kdbx` (which exists on disk), it's a real risk.

> **Recommendation:** Ensure production databases use different passwords. Consider reading test passwords from environment variables.

---

### 13. CORS Feature Enabled but Not Configured
**File:** [Cargo.toml](file:///home/yaakov/Keepass-Sync/backend/Cargo.toml#L14)

The `cors` feature is enabled for `tower-http`, but no CORS layer is configured in the router. This means if CORS is added later, it might be misconfigured with overly permissive defaults (e.g., `CorsLayer::permissive()`).

> **Recommendation:** Either remove the unused `cors` feature or explicitly configure a restrictive CORS policy.

---

### 14. Leftover AI Studio Configuration
**Files:** [.env.example](file:///home/yaakov/Keepass-Sync/.env.example), [vite.config.ts](file:///home/yaakov/Keepass-Sync/vite.config.ts#L11), [index.html](file:///home/yaakov/Keepass-Sync/index.html#L6)

The project retains artifacts from Google AI Studio scaffolding:
- `.env.example` references `GEMINI_API_KEY` and `APP_URL`
- `vite.config.ts` injects `GEMINI_API_KEY` into the frontend
- `index.html` title is "My Google AI Studio App"
- `@google/genai` is listed in `package.json` dependencies

These are unused but add confusion and potential attack surface.

> **Recommendation:** Clean up these remnants.

---

## Summary Table

| # | Severity | Issue | Category |
|---|----------|-------|----------|
| 1 | 🔴 Critical | No authentication on API endpoints | AuthN |
| 2 | 🔴 Critical | No rate limiting (brute-force) | AuthN |
| 3 | 🔴 Critical | No TLS — passwords in cleartext | Transport |
| 4 | 🟠 High | API key leaked to frontend bundle | Secrets |
| 5 | 🟠 High | Error details returned to client | Info Leak |
| 6 | 🟠 High | No upload size limit (DoS) | Availability |
| 7 | 🟠 High | Docker container runs as root | Infra |
| 8 | 🟡 Medium | Downloads don't require password | AuthZ |
| 9 | 🟡 Medium | Database name enumeration | Info Leak |
| 10 | 🟡 Medium | Android `allowBackup=true` | Mobile |
| 11 | 🟡 Medium | No security headers | Web |
| 12 | 🔵 Low | Hardcoded test password in VCS | Secrets |
| 13 | 🔵 Low | CORS enabled but unconfigured | Config |
| 14 | 🔵 Low | Leftover AI Studio artifacts | Hygiene |
