# ClipSync - Parallel Development Plan

> **Goal**: Enable true parallel development across Go Server, Windows WPF Client, and Android Kotlin Client with zero blocking dependencies.

---

## Table of Contents
1. [Shared Protocol Specification](#1-shared-protocol-specification)
2. [Project Structure](#2-project-structure)
3. [Phase Breakdown](#3-phase-breakdown)
4. [Mock/Stub Strategy](#4-mockstub-strategy)
5. [Integration Milestones](#5-integration-milestones)
6. [Risk Assessment](#6-risk-assessment)
7. [Parallel Execution Matrix](#7-parallel-execution-matrix)

---

## 1. Shared Protocol Specification

### 1.1 WebSocket Message Format (Port 8080)

All WebSocket messages follow this envelope:

```json
{
  "type": "string",       // Message type identifier
  "version": 1,           // Protocol version
  "timestamp": 0,         // Unix milliseconds
  "device_id": "string",  // Unique device identifier
  "payload": {}           // Type-specific payload
}
```

#### 1.1.1 Message Types

| Type | Direction | Description |
|------|-----------|-------------|
| `auth` | C→S | Authentication with token |
| `auth_response` | S→C | Auth success/failure |
| `heartbeat` | C→S | Client heartbeat (every 30s) |
| `heartbeat_ack` | S→C | Server heartbeat acknowledgment |
| `clipboard_push` | C→S | Push clipboard content to server |
| `clipboard_sync` | S→C | Broadcast clipboard to other devices |
| `clipboard_pull` | C→S | Request clipboard history |
| `clipboard_history` | S→C | Return clipboard history |
| `device_list` | C→S | Request registered devices |
| `device_list_response` | S→C | Return device list |
| `device_unregister` | C→S | Unregister a device |
| `error` | S→C | Error notification |
| `ping` | S→C | Server ping (connection check) |
| `pong` | C→S | Client pong response |

#### 1.1.2 Message Payloads

**auth**
```json
{
  "type": "auth",
  "payload": {
    "token": "string"  // JWT or session token from HTTP auth
  }
}
```

**auth_response**
```json
{
  "type": "auth_response",
  "payload": {
    "success": true,
    "device_id": "string",
    "message": "string"  // Error message if success=false
  }
}
```

**heartbeat**
```json
{
  "type": "heartbeat",
  "payload": {
    "seq": 0  // Sequence number for debugging
  }
}
```

**clipboard_push**
```json
{
  "type": "clipboard_push",
  "payload": {
    "content_type": "text|image|file",
    "content": "string",       // Base64 for image/file, plain text for text
    "format": "string",        // MIME type or "text/plain"
    "size": 0,                 // Byte size
    "checksum": "string"       // SHA256 hash for deduplication
  }
}
```

**clipboard_sync** (broadcast to other devices)
```json
{
  "type": "clipboard_sync",
  "payload": {
    "source_device_id": "string",
    "source_device_name": "string",
    "content_type": "text|image|file",
    "content": "string",
    "format": "string",
    "size": 0,
    "checksum": "string",
    "encrypted": false         // Whether payload is AES-256 encrypted
  }
}
```

**clipboard_pull**
```json
{
  "type": "clipboard_pull",
  "payload": {
    "limit": 20,              // Number of items to retrieve (default 20, max 50)
    "after_id": 0             // Optional: get items after this history ID
  }
}
```

**clipboard_history**
```json
{
  "type": "clipboard_history",
  "payload": {
    "items": [
      {
        "id": 0,
        "content_type": "text|image|file",
        "content": "string",
        "format": "string",
        "size": 0,
        "checksum": "string",
        "source_device_id": "string",
        "source_device_name": "string",
        "created_at": 0        // Unix milliseconds
      }
    ],
    "total": 0,
    "has_more": false
  }
}
```

**device_list_response**
```json
{
  "type": "device_list_response",
  "payload": {
    "devices": [
      {
        "device_id": "string",
        "device_name": "string",
        "platform": "windows|android",
        "last_seen": 0,        // Unix milliseconds
        "is_online": false
      }
    ]
  }
}
```

**error**
```json
{
  "type": "error",
  "payload": {
    "code": "string",          // Error code (e.g., "AUTH_FAILED", "RATE_LIMITED")
    "message": "string"
  }
}
```

### 1.2 HTTP API Contracts (Port 8081)

#### 1.2.1 Authentication

**POST /api/v1/auth/login**
```
Request:
Content-Type: application/json
{
  "username": "string",
  "password": "string",
  "device_name": "string",    // Human-readable device name
  "platform": "windows|android"
}

Response 200:
{
  "success": true,
  "token": "string",          // JWT token for WebSocket auth
  "device_id": "string",      // Server-assigned device ID
  "expires_at": 0             // Unix milliseconds
}

Response 401:
{
  "success": false,
  "error": "INVALID_CREDENTIALS"
}
```

**POST /api/v1/auth/register**
```
Request:
Content-Type: application/json
{
  "username": "string",
  "password": "string",
  "device_name": "string",
  "platform": "windows|android"
}

Response 201:
{
  "success": true,
  "token": "string",
  "device_id": "string",
  "expires_at": 0
}

Response 409:
{
  "success": false,
  "error": "USERNAME_EXISTS"
}
```

**POST /api/v1/auth/refresh**
```
Request:
Authorization: Bearer <token>

Response 200:
{
  "success": true,
  "token": "string",
  "expires_at": 0
}
```

#### 1.2.2 File Upload (for large clipboard content)

**POST /api/v1/upload**
```
Request:
Authorization: Bearer <token>
Content-Type: multipart/form-data
- file: <binary>
- checksum: <sha256>

Response 200:
{
  "success": true,
  "file_id": "string",
  "download_url": "/api/v1/download/<file_id>"
}
```

**GET /api/v1/download/{file_id}**
```
Request:
Authorization: Bearer <token>

Response 200:
Content-Type: <original mime type>
<binary content>

Response 404:
{
  "error": "FILE_NOT_FOUND"
}
```

#### 1.2.3 Device Management

**GET /api/v1/devices**
```
Request:
Authorization: Bearer <token>

Response 200:
{
  "devices": [
    {
      "device_id": "string",
      "device_name": "string",
      "platform": "windows|android",
      "last_seen": 0,
      "is_online": false,
      "created_at": 0
    }
  ]
}
```

**DELETE /api/v1/devices/{device_id}**
```
Request:
Authorization: Bearer <token>

Response 200:
{
  "success": true
}
```

#### 1.2.4 Health Check

**GET /api/v1/health**
```
Response 200:
{
  "status": "ok",
  "version": "1.0.0",
  "uptime": 0,
  "connected_clients": 0
}
```

### 1.3 Encryption Specification (AES-256)

For sensitive clipboard content, use AES-256-CBC:

```
Key derivation: PBKDF2-SHA256(password, salt, 10000 iterations, 32 bytes)
IV: 16 bytes random
Padding: PKCS#7

Encrypted payload format:
{
  "encrypted": true,
  "content": "<base64(IV + ciphertext)>",
  "salt": "<base64(salt)>",
  "algorithm": "AES-256-CBC"
}
```

**Note**: For MVP, encryption can be optional. Enable via `encrypted: true` flag in clipboard messages.

### 1.4 Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `AUTH_FAILED` | 401 | Invalid credentials or token |
| `TOKEN_EXPIRED` | 401 | Token has expired |
| `RATE_LIMITED` | 429 | Too many requests |
| `INVALID_PAYLOAD` | 400 | Malformed message |
| `CONTENT_TOO_LARGE` | 413 | Content exceeds size limit (5MB) |
| `DEVICE_NOT_FOUND` | 404 | Device ID not found |
| `INTERNAL_ERROR` | 500 | Server internal error |
| `DUPLICATE_CONTENT` | 409 | Same checksum already exists |

---

## 2. Project Structure

### 2.1 Go Server

```
clipSync-server/
├── cmd/
│   └── server/
│       └── main.go              # Entry point
├── internal/
│   ├── config/
│   │   └── config.go            # Configuration management
│   ├── auth/
│   │   ├── auth.go              # Authentication logic
│   │   ├── jwt.go               # JWT token handling
│   │   └── middleware.go        # HTTP auth middleware
│   ├── websocket/
│   │   ├── hub.go               # WebSocket hub (connection management)
│   │   ├── client.go            # Individual client handler
│   │   ├── handler.go           # Message type handlers
│   │   └── protocol.go          # Protocol message types
│   ├── http/
│   │   ├── server.go            # HTTP server setup
│   │   ├── auth_handler.go      # Auth endpoints
│   │   ├── upload_handler.go    # File upload/download
│   │   ├── device_handler.go    # Device management
│   │   └── health_handler.go    # Health check
│   ├── database/
│   │   ├── db.go                # SQLite connection
│   │   ├── models.go            # Database models
│   │   ├── migrations.go        # Schema migrations
│   │   ├── clipboard_repo.go    # Clipboard CRUD
│   │   ├── device_repo.go       # Device CRUD
│   │   └── user_repo.go         # User CRUD
│   ├── encryption/
│   │   └── aes.go               # AES-256 utilities
│   └── heartbeat/
│       └── monitor.go           # Heartbeat timeout detection
├── pkg/
│   └── protocol/
│       ├── messages.go          # Shared message structs
│       └── schema.go            # JSON schema validation
├── migrations/
│   ├── 001_initial.up.sql       # Initial schema
│   └── 001_initial.down.sql
├── configs/
│   └── config.yaml              # Default configuration
├── scripts/
│   └── mock_server.go           # Mock server for client testing
├── tests/
│   ├── auth_test.go
│   ├── websocket_test.go
│   └── api_test.go
├── go.mod
├── go.sum
├── Makefile
└── README.md
```

### 2.2 Windows WPF Client

```
clipSync-windows/
├── ClipSync.WPF/
│   ├── App.xaml.cs              # Application entry
│   ├── App.xaml
│   ├── MainWindow.xaml.cs       # Main UI window
│   ├── MainWindow.xaml
│   ├── ClipSync.WPF.csproj
│   ├── Core/
│   │   ├── ClipboardMonitor.cs  # System clipboard watcher
│   │   ├── SyncEngine.cs        # Sync orchestration
│   │   ├── EncryptionHelper.cs  # AES-256 implementation
│   │   └── SettingsManager.cs   # Local settings storage
│   ├── Network/
│   │   ├── WebSocketClient.cs   # WebSocket connection
│   │   ├── HttpClient.cs        # HTTP API client
│   │   ├── Protocol.cs          # Message serialization
│   │   ├── ReconnectHandler.cs  # Auto-reconnect logic
│   │   └── HeartbeatTimer.cs    # 30s heartbeat
│   ├── UI/
│   │   ├── Controls/
│   │   │   ├── ClipboardItemControl.xaml.cs
│   │   │   └── DeviceListControl.xaml.cs
│   │   ├── ViewModels/
│   │   │   ├── MainViewModel.cs
│   │   │   ├── SettingsViewModel.cs
│   │   │   └── HistoryViewModel.cs
│   │   └── Views/
│   │       ├── SettingsView.xaml
│   │       └── HistoryView.xaml
│   ├── Storage/
│   │   ├── LocalDatabase.cs     # SQLite local cache
│   │   └── FileCache.cs         # Image/file caching
│   ├── SystemTray/
│   │   ├── TrayIcon.cs          # System tray integration
│   │   └── AutoStart.cs         # Windows startup registration
│   └── Resources/
│       ├── Styles.xaml
│       └── Icons/
├── ClipSync.WPF.Tests/
│   ├── ClipboardMonitorTests.cs
│   └── ProtocolTests.cs
├── ClipSync.WPF.sln
├── app.manifest
└── README.md
```

### 2.3 Android Kotlin Client

```
clipSync-android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/clipsync/app/
│   │   │   │   ├── ClipSyncApplication.kt      # App entry point
│   │   │   │   ├── MainActivity.kt             # Main activity
│   │   │   │   ├── core/
│   │   │   │   │   ├── ClipboardMonitor.kt     # Clipboard listener
│   │   │   │   │   ├── SyncEngine.kt           # Sync orchestration
│   │   │   │   │   ├── EncryptionHelper.kt     # AES-256
│   │   │   │   │   └── SettingsManager.kt      # SharedPreferences
│   │   │   │   ├── network/
│   │   │   │   │   ├── WebSocketClient.kt      # WebSocket connection
│   │   │   │   │   ├── ApiClient.kt            # Retrofit HTTP client
│   │   │   │   │   ├── Protocol.kt             # Message serialization
│   │   │   │   │   ├── ReconnectHandler.kt     # Auto-reconnect
│   │   │   │   │   └── HeartbeatManager.kt     # 30s heartbeat
│   │   │   │   ├── service/
│   │   │   │   │   ├── ClipboardService.kt     # Foreground service
│   │   │   │   │   └── BootReceiver.kt         # Auto-start on boot
│   │   │   │   ├── data/
│   │   │   │   │   ├── AppDatabase.kt          # Room database
│   │   │   │   │   ├── ClipboardDao.kt         # Clipboard DAO
│   │   │   │   │   ├── DeviceDao.kt            # Device DAO
│   │   │   │   │   └── entities/               # Room entities
│   │   │   │   ├── ui/
│   │   │   │   │   ├── theme/                  # Material 3 theme
│   │   │   │   │   ├── screens/
│   │   │   │   │   │   ├── HomeScreen.kt
│   │   │   │   │   │   ├── HistoryScreen.kt
│   │   │   │   │   │   ├── SettingsScreen.kt
│   │   │   │   │   │   └── LoginScreen.kt
│   │   │   │   │   └── components/             # Reusable composables
│   │   │   │   └── viewmodel/
│   │   │   │       ├── MainViewModel.kt
│   │   │   │       └── SettingsViewModel.kt
│   │   │   └── res/
│   │   │       ├── drawable/
│   │   │       ├── mipmap/
│   │   │       └── values/
│   │   └── test/
│   │       └── java/com/clipsync/app/
│   │           ├── ProtocolTest.kt
│   │           └── EncryptionTest.kt
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 3. Phase Breakdown

### Phase 0: Foundation (Week 1) - ALL PARALLEL

**Goal**: All 3 tracks can start immediately with zero dependencies.

| Track | Deliverables | Dependencies |
|-------|-------------|--------------|
| **Server** | - Project scaffolding<br>- Config system<br>- SQLite connection with WAL<br>- Protocol message structs (Go)<br>- Mock server script | None |
| **Windows** | - WPF project scaffolding<br>- Protocol message classes (C#)<br>- Mock WebSocket server (local)<br>- Basic UI shell | Protocol spec only |
| **Android** | - Android project scaffolding<br>- Protocol data classes (Kotlin)<br>- Mock server config<br>- Basic Compose UI shell | Protocol spec only |

**Parallel Execution**: All 3 tracks start Day 1. Clients use mock servers.

### Phase 1: Core Infrastructure (Week 2-3) - ALL PARALLEL

| Track | Deliverables | Dependencies |
|-------|-------------|--------------|
| **Server** | - HTTP auth endpoints (login/register/refresh)<br>- JWT token generation<br>- SQLite schema + migrations<br>- User/device models<br>- Health check endpoint | Phase 0 |
| **Windows** | - Clipboard monitoring (system events)<br>- Local settings storage<br>- HTTP client (auth flow)<br>- WebSocket client skeleton<br>- Encryption helper (AES-256) | Phase 0 + Protocol spec |
| **Android** | - Clipboard monitoring (ClipboardManager)<br>- SharedPreferences settings<br>- Retrofit HTTP client<br>- WebSocket client skeleton<br>- Encryption helper | Phase 0 + Protocol spec |

**Parallel Execution**: All 3 tracks continue independently. Clients test against mock server.

### Phase 2: WebSocket & Sync (Week 4-5) - ALL PARALLEL

| Track | Deliverables | Dependencies |
|-------|-------------|--------------|
| **Server** | - WebSocket hub (connection management)<br>- Message routing/handling<br>- Auth middleware for WS<br>- Heartbeat monitoring<br>- Clipboard broadcast logic<br>- Auto-reconnect support | Phase 1 |
| **Windows** | - WebSocket message handling<br>- Heartbeat timer (30s)<br>- Auto-reconnect logic<br>- Clipboard push on change<br>- Clipboard sync receiver<br>- Local clipboard history (20-50 items) | Phase 1 |
| **Android** | - WebSocket message handling<br>- Heartbeat manager (30s)<br>- Auto-reconnect logic<br>- Clipboard push on change<br>- Clipboard sync receiver<br>- Room DB for history | Phase 1 |

**Parallel Execution**: All 3 tracks continue. Clients can test WS logic against mock server with simulated responses.

### Phase 3: Features & Polish (Week 6-7) - ALL PARALLEL

| Track | Deliverables | Dependencies |
|-------|-------------|--------------|
| **Server** | - File upload/download endpoints<br>- Device management API<br>- Rate limiting<br>- Connection limits (2-core optimization)<br>- Logging & error handling<br>- WAL mode optimization | Phase 2 |
| **Windows** | - System tray integration<br>- Auto-start on boot<br>- Image clipboard support<br>- File snippet support<br>- Device list UI<br>- Settings UI<br>- History UI | Phase 2 |
| **Android** | - Foreground service<br>- Boot receiver (auto-start)<br>- Image clipboard support<br>- Notification management<br>- Device list screen<br>- Settings screen<br>- History screen | Phase 2 |

**Parallel Execution**: All 3 tracks continue. First integration testing begins (see Section 5).

### Phase 4: Integration & Testing (Week 8) - CONVERGENCE

| Track | Deliverables | Dependencies |
|-------|-------------|--------------|
| **All** | - End-to-end testing<br>- Cross-platform sync verification<br>- Performance testing (2-core server)<br>- Bug fixes<br>- Error handling edge cases<br>- Security audit | Phase 3 complete |

---

## 4. Mock/Stub Strategy

### 4.1 Mock Server (Go)

Located at `clipSync-server/scripts/mock_server.go`:

```go
// Mock server that simulates all server responses
// Runs on localhost:8080 (WS) and localhost:8081 (HTTP)
// No database required - in-memory state

Features:
- Accepts any login credentials (returns valid token)
- Simulates WebSocket connections
- Echoes clipboard messages to simulate sync
- Simulates heartbeat responses
- Simulates device list responses
- Configurable latency (default: 50ms)
- Configurable error injection (for testing resilience)
```

**Usage**:
```bash
# Start mock server
go run scripts/mock_server.go

# With options
go run scripts/mock_server.go --latency=100ms --error-rate=0.1
```

### 4.2 Client-Side Mocks

#### Windows Mock Provider
```csharp
// ClipSync.WPF/Network/MockWebSocketClient.cs
// Implements IWebSocketClient interface
// Can be swapped in via dependency injection

public interface IWebSocketClient
{
    Task ConnectAsync(string url);
    Task SendAsync(string message);
    event EventHandler<string> MessageReceived;
    event EventHandler Connected;
    event EventHandler Disconnected;
}

// Usage in development:
// services.AddSingleton<IWebSocketClient, MockWebSocketClient>();
// Production:
// services.AddSingleton<IWebSocketClient, WebSocketClient>();
```

#### Android Mock Provider
```kotlin
// clipSync-android/app/src/main/java/.../network/MockWebSocketClient.kt
// Implements WebSocketClient interface

interface WebSocketClient {
    suspend fun connect(url: String)
    suspend fun send(message: String)
    val messages: Flow<String>
    val connectionState: StateFlow<ConnectionState>
}

// Usage via Hilt/Dagger:
// @Provides @Development fun provideWebSocketClient() = MockWebSocketClient()
// @Provides @Production fun provideWebSocketClient() = RealWebSocketClient()
```

### 4.3 Mock Data Generator

Generate realistic test data for client development:

```json
// mock_data.json
{
  "clipboard_items": [
    {
      "content_type": "text",
      "content": "Hello, this is a test clipboard item",
      "format": "text/plain",
      "source_device_name": "Desktop-PC"
    },
    {
      "content_type": "image",
      "content": "<base64_encoded_png>",
      "format": "image/png",
      "source_device_name": "Pixel-7"
    }
  ],
  "devices": [
    {
      "device_id": "dev-001",
      "device_name": "Desktop-PC",
      "platform": "windows",
      "is_online": true
    },
    {
      "device_id": "dev-002",
      "device_name": "Pixel-7",
      "platform": "android",
      "is_online": false
    }
  ]
}
```

### 4.4 Interface-First Development

**Critical**: All clients must code against interfaces, not implementations.

```
┌─────────────────────────────────────────────────┐
│                   Protocol Spec                  │
│              (Single Source of Truth)            │
└─────────────────────────────────────────────────┘
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
┌───────────────┐ ┌────────┐ ┌──────────┐
│ IWebSocketClient│ │IHttpClient│ │IClipboard│
│ (interface)   │ │(interface)│ │(interface)│
└───────┬───────┘ └───┬────┘ └────┬─────┘
        │             │           │
   ┌────┴────┐   ┌────┴───┐  ┌────┴────┐
   │  Mock   │   │  Mock  │  │  Mock   │
   │  Real   │   │  Real  │  │  Real   │
   └─────────┘   └────────┘  └─────────┘
```

---

## 5. Integration Milestones

### M1: Protocol Compatibility (End of Week 2)

**Goal**: Verify all 3 tracks can serialize/deserialize protocol messages correctly.

**Test**:
1. Generate test messages from protocol spec
2. Each track serializes and deserializes all message types
3. Compare JSON output across implementations
4. Verify checksum calculations match

**Success Criteria**: All message types round-trip correctly across all 3 implementations.

### M2: Auth Flow Integration (End of Week 3)

**Goal**: Clients can authenticate against real server.

**Test**:
1. Start real server with test database
2. Windows client: register → login → receive token
3. Android client: register → login → receive token
4. Verify tokens work for HTTP API calls
5. Verify token refresh works

**Success Criteria**: Both clients can complete full auth flow.

### M3: WebSocket Connection (End of Week 4)

**Goal**: Clients can connect, authenticate, and maintain WebSocket connections.

**Test**:
1. Start server
2. Both clients connect via WebSocket
3. Authenticate on WebSocket
4. Verify heartbeat exchange (30s intervals)
5. Verify server shows both clients as online
6. Test auto-reconnect (kill server, restart, verify reconnect)

**Success Criteria**: Stable connections with heartbeat for 10+ minutes.

### M4: Clipboard Sync (End of Week 5)

**Goal**: End-to-end clipboard sync between all devices.

**Test**:
1. Copy text on Windows → verify appears on Android
2. Copy text on Android → verify appears on Windows
3. Copy image on Windows → verify appears on Android
4. Verify clipboard history is populated
5. Verify deduplication (same content not synced twice)

**Success Criteria**: Real-time sync works bidirectionally for text and images.

### M5: Full Feature Integration (End of Week 7)

**Goal**: All features working end-to-end.

**Test**:
1. Device management (view, unregister devices)
2. File upload/download
3. Settings persistence
4. Auto-start behavior
5. System tray / foreground service
6. Performance under load (multiple rapid clipboard changes)

**Success Criteria**: All features functional, no critical bugs.

### M6: Production Readiness (End of Week 8)

**Goal**: Production deployment ready.

**Test**:
1. Deploy to 2-core 2G cloud server
2. Run 24-hour stability test
3. Memory leak detection
4. Database performance (WAL mode verification)
5. Error recovery scenarios
6. Security audit (token handling, encryption)

**Success Criteria**: Stable 24-hour run, memory usage < 500MB, all error scenarios handled.

---

## 6. Risk Assessment

### 6.1 Blocking Risks

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| **Protocol changes mid-development** | HIGH | MEDIUM | Freeze protocol spec after Phase 0. Any changes require version bump and backward compatibility. |
| **Server delays block client testing** | HIGH | LOW | Mock server strategy eliminates this. Clients never blocked. |
| **Platform-specific clipboard APIs differ** | MEDIUM | HIGH | Phase 0 includes platform research. Document differences early. |
| **AES-256 implementation incompatibility** | HIGH | MEDIUM | Use standard libraries only. Test encryption round-trip in M1. |
| **SQLite WAL mode behavior differences** | LOW | LOW | Server-only concern. Test early in Phase 1. |
| **WebSocket connection limits on 2-core server** | MEDIUM | MEDIUM | Load test in Phase 3. Implement connection pooling if needed. |
| **Android background service restrictions** | HIGH | HIGH | Research Android 12+ background limits in Phase 0. Use foreground service with notification. |
| **Windows system tray API changes** | LOW | LOW | Use established libraries (Hardcodet.NotifyIcon.Wpf). |

### 6.2 Parallel Development Risks

| Risk | Mitigation |
|------|------------|
| **Interface drift between tracks** | Protocol spec is single source of truth. Generate code from spec where possible. |
| **Message format inconsistencies** | M1 integration test catches this early. Use JSON schema validation. |
| **Timing assumptions differ** | Document all timing assumptions (heartbeat interval, reconnect delays, timeouts). |
| **Error handling inconsistencies** | Define error codes in protocol spec. Each track must handle all defined errors. |

### 6.3 Resource Risks

| Risk | Mitigation |
|------|------------|
| **2-core 2G server insufficient** | Profile early. Optimize SQLite queries. Implement connection limits. |
| **Memory leaks in long-running clients** | Implement memory monitoring. Add automatic restart on memory threshold. |
| **Database growth unbounded** | Implement history limits (20-50 items). Add cleanup job. |

---

## 7. Parallel Execution Matrix

```
Week:     1        2        3        4        5        6        7        8
         ┌────────────────────────────────────────────────────────────────┐
Server   │ P0: Scaffold    │ P1: Auth API     │ P2: WebSocket    │ P3: Features   │ P4: Integration
         │ + Mock Server   │ + JWT + DB       │ + Hub + Broadcast│ + File Upload  │ + Testing
         ├─────────────────┼──────────────────┼──────────────────┼────────────────┼────────────────┤
Windows  │ P0: Scaffold    │ P1: Clipboard    │ P2: WS Client    │ P3: Tray + UI  │ P4: Integration
         │ + Protocol      │ + HTTP Client    │ + Heartbeat      │ + Auto-start   │ + Testing
         │ + Mock Provider │ + Encryption     │ + Reconnect      │ + History UI   │
         ├─────────────────┼──────────────────┼──────────────────┼────────────────┼────────────────┤
Android  │ P0: Scaffold    │ P1: Clipboard    │ P2: WS Client    │ P3: Service+UI │ P4: Integration
         │ + Protocol      │ + Retrofit       │ + Heartbeat      │ + Foreground   │ + Testing
         │ + Mock Provider │ + Encryption     │ + Reconnect      │ + Boot Receiver│
         └─────────────────┴──────────────────┴──────────────────┴────────────────┴────────────────┘

         ◄──── ALL PARALLEL ────► ◄──── ALL PARALLEL ────► ◄── CONVERGENCE ──►

         Integration Points:
         M1: Protocol Compatibility ──────────────────────▲
         M2: Auth Flow Integration ───────────────────────────────▲
         M3: WebSocket Connection ──────────────────────────────────────▲
         M4: Clipboard Sync ─────────────────────────────────────────────────▲
         M5: Full Feature Integration ──────────────────────────────────────────────▲
         M6: Production Readiness ───────────────────────────────────────────────────────▲
```

### Key Parallel Opportunities

1. **Day 1**: All 3 tracks start simultaneously with protocol spec
2. **Week 1-2**: Complete independence - no integration needed
3. **Week 3**: First integration point (M1) - protocol compatibility only
4. **Week 4-5**: Server must be functional for M2/M3, but clients continue feature development
5. **Week 6-7**: Full parallel feature development with periodic integration testing
6. **Week 8**: Convergence for final testing

---

## Appendix A: Quick Start Commands

### Server
```bash
cd clipSync-server
go mod init clipsync-server
go mod tidy
go run cmd/server/main.go          # Real server
go run scripts/mock_server.go      # Mock server
```

### Windows
```bash
cd clipSync-windows
dotnet new wpf -n ClipSync.WPF
dotnet build
dotnet run
```

### Android
```bash
cd clipSync-android
# Create via Android Studio or:
sdkmanager "platforms;android-34" "build-tools;34.0.0"
./gradlew assembleDebug
```

---

## Appendix B: Protocol Spec Versioning

- Current version: **v1**
- Version is included in every WebSocket message
- Breaking changes require version bump
- Server should support backward compatibility for N-1 versions
- Clients should negotiate version on auth

---

## Appendix C: Size Limits

| Resource | Limit | Reason |
|----------|-------|--------|
| Text clipboard | 100KB | Reasonable text limit |
| Image clipboard | 5MB | Server memory constraints |
| File snippet | 5MB | Upload size limit |
| Clipboard history | 50 items | Database performance |
| Connected devices | 10 per user | 2-core server limits |
| Heartbeat timeout | 90s | 3x heartbeat interval |
| Reconnect backoff | 1s → 60s | Exponential backoff max |

---

*Document Version: 1.0*
*Last Updated: 2026-04-25*
*Status: Ready for Execution*
