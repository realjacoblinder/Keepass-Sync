# Known Bugs & Issues

This document tracks identified bugs, potential root causes, and recommended fixes for the KeePass Sync project.

## High Priority

### 1. "Incorrect Password" error during Sync
**Symptom:** The Web UI (and potentially the Android app) frequently reports "Incorrect Password" or "Failed to decrypt" even when the correct password is provided.
**Status:** Fixed

#### Possible Root Causes:

1. **Buffer vs. ArrayBuffer Pool Issue (Critical)**
   - **Location:** `server.ts` (Lines 45, 59)
   - **Details:** The backend uses `new Uint8Array(buffer).buffer` to convert Node.js `Buffer` objects (from `multer` and `fs.readFileSync`) to `ArrayBuffer` for the `kdbxweb` library.
   - **The Bug:** In Node.js, `Buffer` objects are often allocated from an internal shared buffer pool. Accessing the `.buffer` property returns the **entire underlying ArrayBuffer (the pool)**, not just the segment containing the file data.
   - **Result:** `kdbxweb` attempts to parse the entire memory pool starting from offset 0, which contains unrelated data or garbage, leading to a decryption failure that manifests as an "Incorrect Password" error.
   - **Fix:** Use a safe conversion that slices the ArrayBuffer to exactly match the Buffer's content:
     ```typescript
     const arrayBuffer = buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength);
     ```

2. **Server-side Password Sync Assumption**
   - **Details:** The sync logic assumes both the uploaded database and the master database share the **exact same password**.
   - **Scenario:** If a user updates their password locally on a client and tries to sync, the server will successfully decrypt the uploaded file but then fail to decrypt the *master* file using the new password.
   - **Result:** The server returns a 500 error or "Password mismatch" error (Line 64), which the UI may misinterpret or report as a generic failure.

3. **kdbxweb Version Incompatibility**
   - **Details:** `kdbxweb` 2.x introduced changes in how binary data and credentials are handled.
   - **Details:** The use of `ProtectedValue.fromString(password)` is generally correct for v2, but ensure that the password is not being double-encoded (e.g., if the frontend is already hashing it, which it shouldn't be for this backend).

## Low Priority

### 2. Validation Mismatch (Master Name)
- **Symptom:** UI rejects certain names that the server might have sanitized (e.g., underscores).
- **Details:** The frontend uses the regex `^[a-zA-Z0-9-]+$` while the server sanitization `name.replace(/[^a-zA-Z0-9_-]/g, '')` allows underscores.
- **Fix:** Align the regex in `App.tsx` and `SyncScreen.kt` with the server's allowed character set.

### 3. Missing Error Details
- **Symptom:** Debugging is difficult because the server logs "Details omitted to protect sensitive data."
- **Details:** While secure, it obscures legitimate non-sensitive errors (like file system issues).
- **Fix:** Log the error type or a scrubbed message while still hiding the password/keys.
