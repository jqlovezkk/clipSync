# ClipSync - Agent Guide

Cross-platform clipboard sync: **Go server** + **Windows WPF** + **Android Kotlin**. All three tracks share a single protocol spec.

## Architecture

```
clipSync-server/          Go 1.22  — WS (8080) + HTTP API (8081), SQLite+WAL, JWT auth
clipSync-windows/         .NET 8   — WPF desktop, system tray, clipboard monitor
clipSync-android/         Kotlin   — Jetpack Compose, foreground service, Room DB
protocol/                 JSON Schema — single source of truth for all tracks
```

## Developer Commands

### Server (Go)
```bash
cd clipSync-server
make run          # start server (reads configs/config.yaml)
make build        # build to bin/clipsync-server
make build-linux  # cross-compile for Linux
make test         # go test ./... -v -count=1
make clean        # remove bin/ and data/
```

### Windows (WPF)
```bash
cd clipSync-windows
dotnet build                    # build all
dotnet run --project ClipSync.WPF   # run app
```

### Android
```bash
cd clipSync-android
.\gradlew assembleDebug         # build debug APK (Windows)
./gradlew assembleDebug         # build debug APK (Unix)
```

### Protocol Compatibility Test
```powershell
# PowerShell — verifies all 3 tracks implement the same protocol
.\scripts\test-protocol-compatibility.ps1
# Requires server running on localhost:8081 for live checks
```

## Key Ports & Config

| Component | Port | Purpose |
|-----------|------|---------|
| WebSocket | 8080 | Real-time clipboard sync |
| HTTP API  | 8081 | Auth, file upload, device management, health |

- Config: `clipSync-server/configs/config.yaml`
- Override config path: `CLIPSYNC_CONFIG` env var
- DB: `clipSync-server/data/clipsync.db` (SQLite, auto-created)
- **JWT secret is hardcoded in config.yaml — change before production**

## Protocol Spec (Single Source of Truth)

All message types and HTTP contracts are defined in:
- `protocol/ws-messages.schema.json` — 14 WS message types
- `protocol/http-api.schema.json` — HTTP API contracts

**When working on any client or server, these schemas are the authoritative reference.** Do not invent new message types or fields without updating the schema first.

## Server Internals

- Entry: `cmd/server/main.go`
- DB uses `github.com/mattn/go-sqlite3` (requires CGO — `gcc` must be installed)
- Migrations are embedded in code (`internal/database/migrations.go`), not file-based
- WS auth via JWT token passed in `auth` message after HTTP login
- Heartbeat: clients send every 30s, server timeout at 90s
- Rate limiter on auth endpoints: 10 req/min per IP
- Mock server: `clipSync-server/scripts/mock_server.go` for client dev testing

## Windows Client Internals

- Entry: `ClipSync.WPF/App.xaml.cs`
- Uses `CommunityToolkit.Mvvm` for MVVM, `Hardcodet.NotifyIcon.Wpf` for system tray
- Local cache via `Microsoft.Data.Sqlite`
- JSON via `Newtonsoft.Json`
- Image preview via custom `ImagePreviewConverter` (Base64 → BitmapImage)

## Android Client Internals

- Entry: `app/src/main/java/com/clipsync/app/ClipSyncApplication.kt`
- UI: Jetpack Compose with Material 3, Navigation Compose
- Local DB: Room with KSP codegen
- Settings: DataStore Preferences (NOT SharedPreferences)
- WebSocket: OkHttp
- Serialization: kotlinx.serialization
- Clipboard runs as foreground service (`ClipboardService.kt`) with notification
- Auto-start via `BootReceiver.kt`

## Size Limits

| Resource | Limit |
|----------|-------|
| Text clipboard | 100KB |
| Image/file upload | 5MB |
| Clipboard history | 50 items |
| Connected devices | 10 per user |
| Heartbeat timeout | 90s |
| Reconnect backoff | 1s → 60s exponential |

## Gotchas

- **CGO required for Go server** — `go-sqlite3` needs a C compiler. On Windows, ensure `gcc` is in PATH (e.g. via MSYS2 or TDM-GCC).
- **Android KSP codegen** — Room entities require KSP. If DAO/Entity changes don't reflect, run `gradlew clean` then rebuild.
- **Protocol field naming** — all JSON uses `snake_case`. Go struct tags, C# JsonProperty, and Kotlin @SerialName must all match.
- **Timestamps** — all timestamps are Unix milliseconds (not seconds).
- **`.clipsync_secret`** — contains the JWT secret used in config.yaml. Gitignored.

## Existing Docs

- `DEVELOPMENT_PLAN.md` — full parallel development plan with phases, milestones, mock strategy
- `IMAGE_PREVIEW_OPTIMIZATION.md` — Windows image preview implementation details
