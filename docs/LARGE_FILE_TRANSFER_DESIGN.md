# ClipSync 大文件/大图片传输方案

> **目标**：支持通过剪贴板传递大图片（截图、照片等）和大文件，同时不阻塞 WebSocket 实时同步、不撑爆服务器内存。

---

## 1. 问题分析

### 1.1 当前架构瓶颈

| 组件 | 当前限制 | 问题 |
|------|---------|------|
| WebSocket 消息大小 | 1MB 硬编码 | 超过 1MB 的消息直接断连 |
| 剪贴板内容存储 | SQLite TEXT 列存 Base64 | 50 条 × 1MB = 50MB/用户 |
| 传输方式 | 全量 Base64 推送 | 大文件阻塞发送队列，影响其他剪贴板同步 |
| Windows 客户端 | 无大小检查 | 可能尝试发送 10MB+ 的截图 |

### 1.2 典型图片大小

| 场景 | 原始大小 | Base64 后 (+33%) | 能否通过 WS？ |
|------|---------|-------------------|-------------|
| 小图标 (32×32) | ~1KB | ~1.3KB | ✅ |
| 网页截图 (1920×1080 PNG) | ~200KB | ~267KB | ✅ |
| Retina 截图 (3840×2160 PNG) | ~800KB | ~1.07MB | ❌ 超过 1MB |
| 手机照片 (12MP JPEG) | ~3MB | ~4MB | ❌ |
| Windows 全屏截图 (多显示器) | ~5MB | ~6.7MB | ❌ |

### 1.3 核心矛盾

- **实时性**要求：剪贴板变化需要秒级同步
- **大文件**特性：传输慢、占带宽、占内存
- 两者混用同一个通道 → 大文件传输期间，其他剪贴板同步被阻塞

---

## 2. 设计方案：双通道架构

### 2.1 核心思想

```
小内容 (< 512KB)  →  WebSocket 直接推送 Base64
大内容 (≥ 512KB)  →  HTTP 上传文件 → WebSocket 推送 file_id → 接收方 HTTP 下载
```

### 2.2 流程图

```
发送方 (Windows/Android)
│
├─ 检测剪贴板内容大小
│
├─ [ < 512KB ] ────────────────────────┐
│                                       ▼
│                              WebSocket Push
│                              content_type: text/image
│                              content: base64(...)
│                                       │
├─ [ ≥ 512KB ] ────────────────────────┐│
│                                       ▼│
│                          ┌─────────────────────┐
│                          │ HTTP POST /upload   │
│                          │ Content-Type: multipart
│                          │ 得到: file_id + download_url
│                          └──────────┬──────────┘
│                                     ▼
│                              WebSocket Push
│                              content_type: file
│                              file_id: "abc123..."
│                              download_url: "/api/v1/download/abc123"
│                                     │
└─────────────────────────────────────┼──────────────┘
                                      ▼
接收方 (Android/Windows)
│
├─ [ content_type: text/image ] ─────┐
│                                     ▼
│                            直接 Base64 解码
│                            设置到剪贴板
│
├─ [ content_type: file ] ───────────┐
│                                     ▼
│                          ┌─────────────────────┐
│                          │ HTTP GET /download  │
│                          │ 得到: 文件二进制     │
│                          └──────────┬──────────┘
│                                     ▼
│                            保存到临时文件
│                            设置到剪贴板 (URI)
│                            定时清理临时文件
│
└─────────────────────────────────────┘
```

### 2.3 阈值设计

| 阈值 | 值 | 说明 |
|------|-----|------|
| `file_reference_threshold_kb` | **512KB** | 超过此大小走 HTTP 上传 |
| `ws_max_message_size_mb` | **1MB** | WebSocket 消息硬上限 |
| `max_file_size_mb` | **50MB** | HTTP 上传上限 |

**为什么是 512KB？**
- 512KB Base64 ≈ 683KB，远低于 1MB WS 上限，留有余量
- 典型截图 (1920×1080 PNG ~200KB) 仍走 WebSocket，保证实时性
- Retina 截图 (~800KB) 自动走 HTTP，不会断连

---

## 3. 现有基础设施（已实现 ✅）

### 3.1 服务端

| 组件 | 状态 | 文件 |
|------|------|------|
| HTTP 上传端点 | ✅ 已完成 | `internal/httpserver/upload_handler.go` |
| HTTP 下载端点 | ✅ 已完成 | 同上 |
| `uploaded_files` 表 | ✅ 已完成 | `internal/database/migrations.go` (v1) |
| JWT 认证 | ✅ 已完成 | 上传/下载均需 Bearer Token |
| 文件大小限制 | ✅ 可配置 | `config.yaml: max_file_size_mb: 50` |
| 校验和支持 | ✅ 已完成 | SHA256 客户端/服务端双向校验 |
| 路径安全 | ✅ 已完成 | 拒绝 `../` 等遍历攻击 |

### 3.2 协议定义

| 组件 | 状态 | 文件 |
|------|------|------|
| HTTP API 合约 | ✅ 已定义 | `protocol/http-api.schema.json` |
| WebSocket 消息类型 | ⚠️ 需扩展 | `protocol/ws-messages.schema.json` |

当前 WS 消息类型：
```json
"content_type": { "type": "string", "enum": ["text", "image", "file"] }
```
**`file` 类型已存在**，但缺少 `file_id` 和 `download_url` 字段定义。

### 3.3 客户端

| 组件 | 状态 | 说明 |
|------|------|------|
| Windows HTTP 客户端 | ⚠️ 部分完成 | 有 `HttpClient` 类，但未实现上传 |
| Android HTTP 客户端 | ⚠️ 部分完成 | 有 `ApiClient` 类，但未实现上传 |
| Windows 图片接收 | ✅ 已完成 | `Clipboard.SetImage()` 正常工作 |
| Android 图片接收 | ✅ 已修复 | 通过临时文件 URI 设置剪贴板 |

---

## 4. 需要实现的变更

### 4.1 协议层 (`protocol/ws-messages.schema.json`)

在 `clipboard_push` 和 `clipboard_sync` 的 payload 中增加可选字段：

```json
"payload": {
  "type": "object",
  "properties": {
    "content_type": { "type": "string", "enum": ["text", "image", "file"] },
    "content": { "type": "string" },
    "file_id": { "type": "string" },
    "download_url": { "type": "string" },
    "file_size": { "type": "integer" },
    "file_name": { "type": "string" },
    "mime_type": { "type": "string" },
    ...
  },
  "if": { "properties": { "content_type": { "const": "file" } } },
  "then": {
    "required": ["file_id", "download_url", "file_size"],
    "properties": {
      "content": { "type": "string", "maxLength": 0 }
    }
  }
}
```

### 4.2 服务端 (`clipSync-server/`)

| 文件 | 变更 |
|------|------|
| `internal/websocket/handler.go` | `handleClipboardPush` 增加 `file` 类型处理：存储 file_id 引用而非 content |
| `internal/database/migrations.go` | 新增 `clipboard_history` 的 `file_id TEXT` 列（可选，ALTER TABLE） |
| `internal/websocket/hub.go` | 广播时透传 file_id 和 download_url |
| `internal/config/config.go` | ✅ 已完成 |

### 4.3 Windows 客户端 (`clipSync-windows/`)

| 文件 | 变更 |
|------|------|
| `Core/ClipboardMonitor.cs` | 增加图片大小检查（`ImageContent.Length`） |
| `Core/SyncEngine.cs` | 大文件走 HTTP 上传 → 推送 file_id |
| `Network/HttpClient.cs` | 新增 `UploadFileAsync()` 方法 |
| `Core/SyncEngine.cs` | 接收 `file` 类型时 HTTP 下载 → 保存临时文件 → 设置剪贴板 |

### 4.4 Android 客户端 (`clipSync-android/`)

| 文件 | 变更 |
|------|------|
| `core/ClipboardMonitor.kt` | ✅ 已完成大小检查 (`maxContentSizeBytes`) |
| `core/SyncEngine.kt` | 大文件走 HTTP 上传 → 推送 file_id |
| `network/ApiClient.kt` | 新增 `uploadFile()` 方法 |
| `core/SyncEngine.kt` | 接收 `file` 类型时 HTTP 下载 → 保存缓存 → 设置剪贴板 |

---

## 5. 内存与性能分析

### 5.1 2GB 服务器内存评估

| 组件 | 内存占用 | 说明 |
|------|---------|------|
| Go 运行时 | ~50MB | 基础开销 |
| SQLite (WAL) | ~100MB | 连接池 + buffer |
| WebSocket Hub + Clients | ~100MB | 100 并发用户 |
| 发送队列 (256 × 1KB 平均) | ~50MB | 小内容为主 |
| 可用内存 | **~1.7GB** | 充裕 |

**关键**：大文件走 HTTP 后：
- ❌ 不再经过 WebSocket 队列
- ❌ 不再存入 SQLite（只存 file_id 引用，几十字节）
- ❌ 不再占用发送 buffer
- ✅ 文件直接写到磁盘，零内存拷贝

### 5.2 并发能力估算

| 场景 | 每用户内存 | 最大并发用户 |
|------|-----------|------------|
| 纯文本 | ~2MB | **850+** |
| 混合（大部分文本 + 偶尔图片） | ~5MB | **340+** |
| 频繁大文件（HTTP 模式） | ~3MB | **560+** |
| 频繁大文件（WS 模式，假设不限制） | ~50MB | **34** |

**结论**：2GB 内存支持 300-500 并发用户完全无压力，瓶颈在网络带宽而非内存。

### 5.3 存储估算

| 场景 | 每用户磁盘 | 100 用户 |
|------|-----------|---------|
| SQLite (50 条文本历史) | ~5MB | ~500MB |
| SQLite (50 条图片引用) | ~5MB | ~500MB |
| 文件存储 (50MB/用户，配额) | 0-50MB | 0-5GB |
| 临时文件 (自动清理) | ~100MB | ~10GB |

建议定期清理：
- 超过 7 天的上传文件
- 超过 24 小时的临时接收文件

---

## 6. 实现优先级

### Phase 1：基础支持 (1-2 天)
- [ ] 扩展 WebSocket 协议：`clipboard_push` 增加 `file_id` 字段
- [ ] 服务端 `handleClipboardPush` 支持 `file` 类型
- [ ] 数据库迁移：`clipboard_history` 增加 `file_id` 列

### Phase 2：Windows 客户端 (2-3 天)
- [ ] `HttpClient.UploadFileAsync()` 实现
- [ ] `SyncEngine` 增加大小判断逻辑
- [ ] 接收 `file` 类型时下载并设置剪贴板

### Phase 3：Android 客户端 (2-3 天)
- [ ] `ApiClient.uploadFile()` 实现
- [ ] `SyncEngine` 增加大小判断逻辑
- [ ] 接收 `file` 类型时下载并设置剪贴板

### Phase 4：清理与优化 (1 天)
- [ ] 临时文件自动清理（定时任务）
- [ ] 服务端过期文件清理（cron job）
- [ ] 传输进度指示（可选）

---

## 7. 安全性考虑

| 威胁 | 防御措施 | 状态 |
|------|---------|------|
| 恶意超大文件 | HTTP 层限制 50MB + WS 层限制 1MB | ✅ 已实现 |
| 文件遍历攻击 | 路径校验（拒绝 `../`） | ✅ 已实现 |
| 越权访问 | JWT 认证 + 用户归属校验 | ✅ 已实现 |
| 校验和篡改 | SHA256 客户端/服务端双向校验 | ✅ 已实现 |
| 临时文件泄漏 | 定时清理 + 应用退出清理 | ⚠️ 需实现 |
| 恶意文件类型 | MIME 类型白名单（仅图片/文档） | ⚠️ 需实现 |

---

## 8. 备选方案对比

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|-------|
| **A. HTTP 上传 + WS 引用** (本方案) | 不阻塞 WS、支持大文件、SQLite 不膨胀 | 需要实现上传/下载逻辑 | ⭐⭐⭐⭐⭐ |
| B. 提高 WS 消息限制到 10MB | 简单，改一行代码 | 阻塞队列、内存飙升、SQLite 膨胀 | ⭐⭐ |
| C. 分片传输 (Chunking) | 不提高 WS 限制 | 实现复杂、需要重组、丢片重传 | ⭐⭐⭐ |
| D. 第三方云存储 (S3/OSS) | 无限容量、CDN 加速 | 依赖外部服务、成本增加 | ⭐⭐⭐ |

**结论**：方案 A 是最佳选择。现有基础设施已经完整（上传/下载端点、数据库表、协议定义），只需在各客户端中集成即可。

---

## 9. 文件清单

本方案涉及的所有文件：

```
clipSync-server/
├── configs/config.yaml                    # ✅ 已更新：文件大小配置
├── internal/config/config.go              # ✅ 已更新：配置结构体
├── internal/httpserver/upload_handler.go  # ✅ 已存在：上传/下载处理
├── internal/websocket/handler.go          # ⚠️ 需修改：支持 file 类型
├── internal/database/migrations.go        # ⚠️ 需修改：增加 file_id 列
└── internal/database/db.go                # 无需修改

clipSync-windows/
├── ClipSync.WPF/Network/HttpClient.cs     # ⚠️ 需新增：UploadFileAsync
├── ClipSync.WPF/Core/SyncEngine.cs        # ⚠️ 需修改：大文件逻辑
├── ClipSync.WPF/Core/ClipboardMonitor.cs  # ⚠️ 需新增：大小检查

clipSync-android/
├── app/src/main/.../network/ApiClient.kt  # ⚠️ 需新增：uploadFile
├── app/src/main/.../core/SyncEngine.kt    # ⚠️ 需修改：大文件逻辑
├── app/src/main/.../core/ClipboardMonitor.kt # ✅ 已有大小检查

protocol/
├── ws-messages.schema.json                # ⚠️ 需扩展：file_id 字段
└── http-api.schema.json                   # ✅ 已定义上传/下载 API
```
