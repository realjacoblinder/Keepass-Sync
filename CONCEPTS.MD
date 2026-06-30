# KeePass Sync — Concepts & Codebase Guide

## Purpose

KeePass Sync is a lightweight web application that acts as a **centralized merge server** for KeePass `.kdbx` database files. Clients upload their local copy, the server merges it with a server-side master copy using `kdbxweb`, and clients can then download the updated master. This enables multiple devices to stay in sync without a full cloud sync service.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────┐
│                    Browser (React SPA)                │
│  src/App.tsx — single-component UI                   │
│  Upload .kdbx + password → POST /api/sync            │
│  Download master         → POST /api/download        │
│  Check status            → POST /api/status          │
└──────────────┬───────────────────────────────────────┘
               │  HTTP (port 3000)
┌──────────────▼───────────────────────────────────────┐
│              Express Server (server.ts)               │
│  • Multer (memory storage) for file uploads           │
│  • kdbxweb for KDBX decrypt / merge / save            │
│  • Vite dev middleware (dev) or static dist (prod)     │
│  • Master DBs stored as flat files in ./data/         │
└──────────────┬───────────────────────────────────────┘
               │
        ./data/<masterName>.kdbx
```

The project is a **monorepo-style single app** — frontend and backend live side-by-side, sharing a single `package.json`.

---

## Tech Stack

| Layer       | Technology                                                  |
| ----------- | ----------------------------------------------------------- |
| Frontend    | React 19, TypeScript, Vite                                  |
| Styling     | Tailwind CSS v4, shadcn/ui (base-nova style), Geist font   |
| Backend     | Express 4, tsx (dev runner), multer (file uploads)          |
| KeePass     | `kdbxweb` v2.1 — the core KDBX parsing/merge library       |
| Icons       | lucide-react                                                |
| Animations  | motion (Framer Motion successor)                            |
| Build       | Vite 6                                                      |

### Origin

The project was scaffolded from **Google AI Studio's app template** (see `README.md`, `metadata.json`, `.env.example`). Remnants include:
- `GEMINI_API_KEY` in `.env.example` and `vite.config.ts` — **not actually used** by the app.
- `@google/genai` in `package.json` — **unused dependency**.
- `metadata.json` with empty name/description — AI Studio boilerplate.
- `package.json` `name` is `"react-example"` — leftover from the template.

---

## File Map

```
.
├── server.ts              # Express server — ALL backend logic lives here
├── src/
│   ├── App.tsx            # The ENTIRE frontend UI (single component)
│   ├── main.tsx           # React DOM entry point
│   └── index.css          # Tailwind + shadcn theme (oklch color tokens)
├── components/ui/         # shadcn/ui primitives (Card, Button, Input, Label, Badge)
├── lib/utils.ts           # cn() helper (clsx + tailwind-merge)
├── index.html             # Vite HTML entry
├── vite.config.ts         # Vite + Tailwind plugin, path aliases
├── tsconfig.json          # TypeScript config (bundler resolution, no emit)
├── components.json        # shadcn/ui configuration
├── package.json           # Dependencies + scripts
├── .env.example           # Template env vars (Gemini key, app URL)
└── metadata.json          # AI Studio metadata (unused)
```

### Notable absences

- **No `data/` directory in the repo** — it is created at runtime by `server.ts` (line 10–12). It is also not in `.gitignore`, so if someone commits it accidentally, master DB files could be exposed.
- **No Dockerfile** — no containerization support yet.
- **No tests** — zero test files, no test framework configured.
- **No authentication/authorization** — anyone with network access can sync/download.

---

## Server API (server.ts)

All three endpoints are **POST** requests (even download/status, which are semantically GETs).

### `POST /api/sync`

**Purpose:** Upload a `.kdbx` file to merge with (or create) a master DB.

| Field        | Source          | Description                                      |
| ------------ | -------------- | ------------------------------------------------ |
| `dbFile`     | multipart file | The `.kdbx` file to merge                        |
| `password`   | form field     | Master password to decrypt both DBs              |
| `masterName` | form field     | Logical name for the master (default: `"master"`) |

**Flow:**
1. Sanitize `masterName` via regex: strip anything not `[a-zA-Z0-9_-]`.
2. Load uploaded file into `kdbxweb.Kdbx` using the supplied password.
3. If `./data/<masterName>.kdbx` exists:
   - Load master DB with the **same password**.
   - Call `masterDb.merge(uploadedDb)` — kdbxweb's built-in merge.
   - Save merged master back to disk.
4. If no master exists: save the uploaded file as-is as the new master.

**Critical assumption:** The uploaded DB and the master DB share the **same password**. If they differ, the server returns a 500 error ("Failed to decrypt master database").

### `POST /api/download`

**Purpose:** Download the master `.kdbx` file.

| Field        | Source    | Description                     |
| ------------ | --------- | ------------------------------- |
| `masterName` | JSON body | Name of the master to download  |

Returns the file via `res.download()`. No password needed — the encrypted `.kdbx` file is sent as-is.

### `POST /api/status`

**Purpose:** Check if a master DB exists and when it was last modified.

| Field        | Source    | Description                    |
| ------------ | --------- | ------------------------------ |
| `masterName` | JSON body | Name of the master to check    |

Returns `{ lastUpdated: <ISO date> | null }`.

---

## Frontend (src/App.tsx)

The entire UI is a **single React component** with no routing. It renders two cards:

1. **Upload & Sync card** — form with master name, file picker, password input, and a sync button.
2. **Download Master card** — shows last-updated timestamp and a download button.

### State management
All state is local `useState` hooks. No global store, no context providers.

### Key behaviors
- **Debounced status polling:** When `masterName` changes, a 300ms debounce timer fires before calling `/api/status` to update the "last updated" display.
- **Client-side validation:** Master name is validated against `/^[a-zA-Z0-9-]+$/` (note: hyphens allowed, but **underscores are not** validated client-side even though the server allows them via `sanitizeName`).
- **File input reset:** After a successful sync, the file input is manually reset by grabbing it via `document.getElementById('file-upload')` and clearing its value.
- **Download mechanism:** Creates a temporary `<a>` element, sets `href` to a blob URL, clicks it programmatically, then cleans up.

---

## Quirks and Gotchas

### 1. Input validation mismatch (client vs. server)
- **Server** sanitizes via `/[^a-zA-Z0-9_-]/g` — allows underscores and hyphens.
- **Client** validates via `/^[a-zA-Z0-9-]+$/` — allows hyphens but **rejects underscores**.
- Neither is "wrong," but the inconsistency means underscores typed by the user will be flagged as invalid in the UI, even though the server would accept them.

### 2. Password sent in cleartext
The master password is submitted as a plain `FormData` field over HTTP. Without HTTPS, the password is transmitted in the clear. There is no TLS enforcement.

### 3. No authentication
There is zero auth. Any client that can reach port 3000 can upload, merge, and download any master database by name.

### 4. Same-password assumption
`kdbxweb` requires credentials to load a KDBX file. The server assumes the uploaded DB and the master DB use **the exact same master password**. There is no mechanism to handle databases with different passwords.

### 5. No backup before merge
`masterDb.merge(uploadedDb)` overwrites `./data/<masterName>.kdbx` in-place. If the merge produces a corrupt or undesirable result, there is no rollback — the previous master is gone.

### 6. Error detail suppression
The server intentionally omits error details in its `console.error` output (line 83) to avoid leaking sensitive data to logs. This makes debugging harder — you won't see stack traces.

### 7. POST for all endpoints
`/api/download` and `/api/status` are POST requests despite being read-only operations. This is likely a deliberate choice to avoid putting the `masterName` in query strings (which would appear in server logs and browser history), but it breaks HTTP semantics and means these endpoints can't be bookmarked or cached.

### 8. Unused dependencies
- `@google/genai` — never imported anywhere. Leftover from AI Studio scaffold.
- `motion` (Framer Motion) — in dependencies but not imported in any component.
- `dotenv` — in dependencies but not explicitly loaded in `server.ts` (Vite's `loadEnv` handles env vars for the frontend).

### 9. `data/` directory not gitignored
Master `.kdbx` files stored in `./data/` are not excluded from git tracking. If committed, encrypted password databases would be pushed to the remote repository.

### 10. Vite in server.ts
In dev mode, the Express server creates a Vite dev server in middleware mode. This means `npm run dev` starts a **single process** that serves both the API and the frontend with HMR. In production, it serves the pre-built `dist/` directory as static files.

### 11. GEMINI_API_KEY is baked into the frontend bundle
`vite.config.ts` uses `define` to inject `process.env.GEMINI_API_KEY` as a compile-time constant. Even though the key isn't used, if set, it would be **embedded in the client-side JavaScript bundle** — a security concern if the key were ever actually used.

---

## Development Workflow

```bash
# Install dependencies
npm install

# Run dev server (Express + Vite HMR on port 3000)
npm run dev

# Build for production
npm run build

# Type-check without emitting
npm run lint
```

- Dev server is started via `tsx server.ts` — tsx is a TypeScript executor that runs the server directly without a separate compile step.
- `npm start` runs `node server.ts` which will **not work** without tsx or a prior compile step, since Node doesn't natively execute `.ts` files without a loader.

---

## kdbxweb Merge Semantics

The merge is handled entirely by `kdbxweb.Kdbx.merge()`. Key behaviors of this library method:

- It performs a **three-way merge** using the internal KDBX change history.
- Entry-level conflicts are resolved by **last-modification-time wins**.
- Deleted entries in one copy are respected unless the other copy has a newer modification.
- Group (folder) structure changes are merged similarly.
- The merge is **lossless** for non-conflicting changes — entries added in either copy appear in the merged result.

This means the server acts as a **passive merge relay** — it doesn't understand or inspect KeePass entries. It simply facilitates the `kdbxweb` merge between whatever the client uploads and whatever the master already contains.

---

## shadcn/ui Integration

shadcn/ui is configured in `base-nova` style with `neutral` base color. Components are installed to `components/ui/` and use:
- `class-variance-authority` (cva) for variant-driven styling
- `clsx` + `tailwind-merge` via the `cn()` utility in `lib/utils.ts`
- Tailwind CSS v4 with the `@tailwindcss/vite` plugin (no `tailwind.config.js` — configuration is done via CSS `@theme` blocks)

Currently installed shadcn components: **Badge, Button, Card, Input, Label**.

---

## Environment Variables

| Variable         | Used By     | Purpose                                       | Actually Used? |
| ---------------- | ----------- | --------------------------------------------- | -------------- |
| `GEMINI_API_KEY` | Vite define | Injected into frontend bundle at build time   | ❌ No          |
| `APP_URL`        | —           | Documented in `.env.example` but not consumed  | ❌ No          |
| `NODE_ENV`       | server.ts   | Determines dev (Vite middleware) vs prod mode  | ✅ Yes         |
| `DISABLE_HMR`    | vite.config | AI Studio flag to disable hot module reload    | ✅ Yes (niche) |
