# ClipSync 大对象传输设计方案

> **目标**：支持大图片和系统文件的跨设备同步，同时保持 WebSocket 小消息通道的实时性，避免服务器内存、SQLite 历史和发送队列被大对象拖垮。

---

## 1. 文档结论

### 1.1 最终选择

采用 **双通道 + 引用式消息模型**：

```text
小内容（文本 / 小图片）      -> WebSocket 直接携带 content
大图片 / 系统文件            -> HTTP 上传二进制
                           -> WebSocket 只发送文件引用 metadata
                           -> 接收端再 HTTP 下载并落地到剪贴板
```

### 1.2 为什么不是直接放宽 WebSocket 上限

- WebSocket 当前读取上限为 **1MB**，而且服务端实现里仍是硬编码 read limit。
- 图片 Base64 会膨胀约 33%，4K 截图很容易超过 1MB。
- 大内容走同一条 WS 通道会阻塞实时同步、增大发送队列、挤占数据库空间。
- 提升上限只能延后问题，不能解决“实时消息”和“大对象传输”本质上是两类负载。

### 1.3 本文档修订重点

本版文档相对于原始方案，补充了以下关键闭环：

- 明确区分 **大图片同步** 与 **系统文件剪贴板同步**，不再混淆。
- 协议从“可选附加字段”升级为“**内联消息 / 引用消息** 两种明确语义”。
- 补齐 `clipboard_history` 的引用字段，保证 **实时广播** 和 **断线恢复** 都能工作。
- 补充文件生命周期、清理策略、去重策略和失败兜底，避免出现悬挂引用。

---

## 2. 范围定义

### 2.1 In Scope

- 文本内容同步。
- 小图片直接走 WebSocket 同步。
- 大图片通过 HTTP 上传后再同步引用。
- Windows 和 Android 接收大图片并恢复为图片剪贴板。
- Windows 和 Android 接收系统文件并恢复为“文件剪贴板 / 可访问文件引用”。
- 断线重连后通过历史记录恢复最近一次同步内容。

### 2.2 Out of Scope

- 视频流、目录树递归同步。
- 超过服务端上限的大文件分片断点续传。
- 第三方对象存储（S3/OSS）接入。
- 服务端在线预览、缩略图生成、内容索引。

### 2.3 关键定义

| 术语 | 定义 |
|------|------|
| 小内容 | 可以直接放入 WS 消息体、不会接近 1MB read limit 的内容 |
| 大图片 | 剪贴板图片存在，但编码后超过引用阈值，改走 HTTP 上传 |
| 系统文件 | 来自剪贴板文件列表，例如 Windows `FileDropList` |
| 引用消息 | WS 中不再携带完整内容，只携带 `file_id` 等元数据 |

---

## 3. 当前实现现状

### 3.1 已有基础设施

| 组件 | 状态 | 说明 |
|------|------|------|
| HTTP 上传接口 | ✅ 已有 | `POST /api/v1/upload` 可写入磁盘并登记 `uploaded_files` |
| HTTP 下载接口 | ✅ 已有 | `GET /api/v1/download/{file_id}`，按用户鉴权 |
| 上传文件表 | ✅ 已有 | `uploaded_files` 已包含文件名、类型、大小、checksum、路径 |
| WS 协议基础 | ✅ 已有 | 已有 `clipboard_push / clipboard_sync / clipboard_history` |
| WS `content_type=file` 枚举 | ✅ 已有 | 但仍缺少引用字段定义与完整语义 |
| Windows HTTP 客户端 | ⚠️ 部分有 | 当前只有登录 / 注册 / 刷新 Token |
| Windows 剪贴板监控 | ⚠️ 仅文本和图片 | 尚未监听系统文件剪贴板 |

### 3.2 当前不足

| 问题 | 现状 |
|------|------|
| WS 上限 | `SetReadLimit(1 * 1024 * 1024)` 仍硬编码为 1MB |
| 协议语义 | `content_type=file` 仍默认依赖 `content` 字段 |
| 数据库存储 | `clipboard_history` 只有 `content`，没有 `file_id` 等引用元数据 |
| 历史恢复 | `clipboard_pull` 返回历史时无法还原文件引用 |
| Windows 文件剪贴板 | 当前未支持 `FileDropList` 读取与设置 |
| 清理闭环 | 上传文件过期清理与历史引用的关系尚未定义 |

---

## 4. 问题分析

### 4.1 当前架构瓶颈

| 组件 | 当前限制 | 结果 |
|------|---------|------|
| WebSocket 消息大小 | 1MB 读取上限 | 大图片直接断连或被拒绝 |
| 剪贴板历史表 | `content TEXT` 存 Base64 | 大图片历史占用明显 |
| 广播方式 | 全量内容透传 | 大对象阻塞发送队列 |
| Windows 客户端 | 无阈值判断 | 可能直接发送多 MB 图片 |

### 4.2 典型图片大小

| 场景 | 原始大小 | Base64 后 | 能否稳定走 WS |
|------|---------|-----------|----------------|
| 小图标 | ~1KB | ~1.3KB | ✅ |
| 1080p 截图 | ~200KB | ~267KB | ✅ |
| 4K 截图 | ~800KB | ~1.07MB | ❌ 风险高 |
| 手机照片 | ~3MB | ~4MB | ❌ |
| 多屏全屏截图 | ~5MB | ~6.7MB | ❌ |

### 4.3 核心矛盾

- **实时同步** 需要低延迟、小消息、低阻塞。
- **大对象传输** 天然高延迟、占带宽、占磁盘。
- 两者放在同一条 WS 路径中，会让最慢的大对象拖慢整个同步系统。

---

## 5. 总体设计

### 5.1 核心思路

```text
文本 / 小图片
  -> 直接创建 inline clipboard message
  -> WebSocket 广播

大图片 / 系统文件
  -> 先上传二进制到 HTTP
  -> 获得 file_id
  -> 创建 reference clipboard message
  -> WebSocket 只广播引用信息
  -> 接收端按需下载并恢复剪贴板
```

### 5.2 设计原则

- **小消息优先**：WebSocket 只负责实时通知，不承担大对象主体传输。
- **协议显式化**：不能只靠“某些可选字段是否出现”猜业务语义。
- **历史可恢复**：实时广播能处理的内容，历史拉取也必须能处理。
- **类型区分明确**：大图片和系统文件都可走引用模式，但接收端恢复方式不同。

### 5.3 类型模型

建议把原先单一 `content_type` 的思维，调整为：

| 字段 | 说明 |
|------|------|
| `content_type` | 业务内容类型：`text` / `image` / `file` |
| `transfer_mode` | 传输模式：`inline` / `reference` |

语义如下：

- `text + inline`：普通文本。
- `image + inline`：小图片，`content` 为 Base64。
- `image + reference`：大图片，内容实体在 HTTP 文件存储中。
- `file + reference`：系统文件剪贴板，内容实体在 HTTP 文件存储中。

> `file + inline` 不支持，避免协议复杂化和实现歧义。

---

## 6. 发送与接收流程

### 6.1 发送端流程

```text
读取本地剪贴板
  -> 判断内容类型（text / image / file）
  -> 计算大小、checksum、mime_type、display_name

if text:
  -> 直接走 inline

if image:
  -> 小于阈值 => inline
  -> 大于等于阈值 => 先 upload，再走 image/reference

if file:
  -> 始终 upload，再走 file/reference
```

### 6.2 接收端流程

```text
收到 WebSocket clipboard_sync
  -> 判断 transfer_mode

if inline:
  -> 直接使用 content 恢复剪贴板

if reference:
  -> 根据 file_id 下载
  -> 校验 checksum
  -> 落盘到临时目录
  -> 按 content_type 恢复：
       image -> 设置图片剪贴板
       file  -> 设置系统文件剪贴板 / URI 引用
  -> 记录临时文件以便清理
```

### 6.3 历史恢复流程

```text
客户端重连
  -> 请求 clipboard_history
  -> 获取最近一条记录
  -> 如果是 inline，直接恢复
  -> 如果是 reference，则再次下载并恢复
```

> 历史记录里必须保留引用元数据，否则 reference 模式无法恢复。

---

## 7. 协议设计

### 7.1 设计要求

- `clipboard_push`、`clipboard_sync`、`clipboard_history.items[]` 三处字段必须一致。
- `reference` 模式下不允许依赖 `content` 承载大对象内容。
- 图片与文件都可以是 `reference`，但客户端恢复动作不同。

### 7.2 推荐字段

```json
{
  "content_type": "text | image | file",
  "transfer_mode": "inline | reference",
  "content": "string",
  "format": "string",
  "size": 123,
  "checksum": "sha256",
  "file_id": "string",
  "download_url": "/api/v1/download/xxx",
  "file_name": "string",
  "mime_type": "string",
  "source_device_id": "string",
  "source_device_name": "string",
  "encrypted": false
}
```

### 7.3 字段约束

#### `inline` 模式

- 必填：`content_type`, `transfer_mode`, `content`, `format`, `size`, `checksum`
- 约束：`transfer_mode = "inline"`
- 约束：`file_id`, `download_url`, `file_name`, `mime_type` 可为空

#### `reference` 模式

- 必填：`content_type`, `transfer_mode`, `file_id`, `download_url`, `file_name`, `mime_type`, `size`, `checksum`
- 约束：`transfer_mode = "reference"`
- 约束：`content` 必须为空字符串或省略
- 约束：当 `content_type = "file"` 时，必须为 `reference`

### 7.4 Schema 变更建议

对 `protocol/ws-messages.schema.json` 做如下调整：

- 为 `clipboard_push.payload` 增加 `transfer_mode`。
- 为 `clipboard_sync.payload` 增加 `transfer_mode`。
- 为 `clipboard_item` 增加 `transfer_mode`, `file_id`, `download_url`, `file_name`, `mime_type`。
- 使用 `if/then` 明确 `reference` 模式的 required 字段。
- 将 `content_type=file` 限制为只允许 `transfer_mode=reference`。

### 7.5 向后兼容策略

为避免一次性破坏旧客户端，可短期兼容：

- 若收到消息中没有 `transfer_mode`：
  - 且 `content` 非空，则按旧版 `inline` 处理。
  - 且 `file_id` 存在，则按 `reference` 处理。
- 新客户端发送时一律带上 `transfer_mode`。
- 兼容窗口结束后，再去掉旧逻辑。

---

## 8. 数据库设计

### 8.1 为什么不能只加 `file_id`

如果 `clipboard_history` 只加 `file_id`，仍然存在问题：

- 历史页无法展示原始文件名。
- 接收端恢复时缺少 `mime_type`，判断逻辑不稳。
- 下载地址如果未来变化，只靠 `file_id` 需要客户端额外拼接。
- 历史项无法判断是大图片还是系统文件。

### 8.2 `clipboard_history` 推荐新增字段

| 列名 | 类型 | 说明 |
|------|------|------|
| `transfer_mode` | TEXT | `inline` / `reference` |
| `file_id` | TEXT NULL | 上传文件 ID |
| `download_url` | TEXT NULL | 下载地址 |
| `file_name` | TEXT NULL | 原始名称 |
| `mime_type` | TEXT NULL | MIME 类型 |

### 8.3 建议后的表语义

- `inline` 模式：`content` 保存实际内容。
- `reference` 模式：`content` 为空字符串，真正内容在上传文件表中。
- `size` 始终表示原始内容字节数。
- `checksum` 统一表示原始内容 SHA256。

### 8.4 迁移策略

新增 migration v2：

```sql
ALTER TABLE clipboard_history ADD COLUMN transfer_mode TEXT NOT NULL DEFAULT 'inline';
ALTER TABLE clipboard_history ADD COLUMN file_id TEXT;
ALTER TABLE clipboard_history ADD COLUMN download_url TEXT;
ALTER TABLE clipboard_history ADD COLUMN file_name TEXT;
ALTER TABLE clipboard_history ADD COLUMN mime_type TEXT;
```

并对旧数据做兼容解释：

- 旧行默认视为 `transfer_mode=inline`
- `content_type=file` 的旧数据如果曾直接塞 `content`，新版本仅做读兼容，不再继续写入该模式

---

## 9. 服务端设计

### 9.1 已有能力

- 上传下载 HTTP 端点已存在。
- `uploaded_files` 表已存在。
- 用户归属校验、路径安全、checksum 校验已具备基础。

### 9.2 需要修改的服务端行为

#### `internal/websocket/handler.go`

`handleClipboardPush` 需要改成两套分支：

- `inline`：
  - 校验 `content`
  - 入库 `clipboard_history.content`
  - 广播 inline 消息

- `reference`：
  - 校验 `file_id`
  - 确认 `uploaded_files` 中存在且归属当前用户
  - 入库引用元数据，不存大内容
  - 广播 reference 消息

#### `internal/database/clipboard_repo.go`

- `AddEntry()` 参数扩展为支持 `transfer_mode`, `file_id`, `download_url`, `file_name`, `mime_type`
- `GetHistory()` 查询这些字段
- `GetLatestByUser()` 查询这些字段

#### `pkg/protocol/messages.go`

为以下结构体新增字段：

- `ClipboardPushPayload`
- `ClipboardSyncPayload`
- `ClipboardItem`

### 9.3 引用校验

服务端收到 `reference` 消息时应额外验证：

- `file_id` 是否存在
- 是否属于当前用户
- `size` 是否与 `uploaded_files.size` 一致
- `checksum` 是否与 `uploaded_files.checksum` 一致
- `mime_type` 是否与上传记录匹配或可接受

### 9.4 下载地址策略

建议把 `download_url` 视为冗余便捷字段：

- 历史中保留它，方便客户端直接调用。
- 真正唯一主键仍然是 `file_id`。
- 若未来路由变化，客户端也可按 `file_id` 拼接最新地址。

---

## 10. 客户端设计

## 10.1 Windows 客户端

### 当前现状

- `ClipboardMonitor` 仅支持文本和图片。
- `HttpClient` 仅支持登录、注册、刷新 Token。
- `SyncEngine` 当前发送逻辑为：
  - 文本直接发字符串
  - 图片直接转 Base64 后走 WebSocket

### 需要新增的能力

#### 剪贴板读取

- 文本：保留现状。
- 图片：增加阈值判断，决定 inline/reference。
- 文件：新增读取 Windows `FileDropList`。

#### 剪贴板写入

- `image + inline`：现有 `Clipboard.SetImage()`。
- `image + reference`：下载后仍用 `Clipboard.SetImage()`。
- `file + reference`：下载后写入临时文件，并用 Windows 文件剪贴板格式设置。

> Windows 这里不能简单写成“设置 URI”；真正可用的复制体验应恢复为文件列表剪贴板。

#### HTTP 能力

`Network/HttpClient.cs` 新增：

- `UploadFileAsync(Stream/filePath, fileName, mimeType, checksum)`
- `DownloadFileAsync(fileId or downloadUrl)`

#### 同步引擎

`Core/SyncEngine.cs` 新增：

- 本地内容大小阈值判断
- 引用消息构造
- 接收引用消息后下载并恢复剪贴板
- 临时文件登记与清理

### Windows 推荐文件改动

| 文件 | 变更 |
|------|------|
| `Core/ClipboardMonitor.cs` | 增加 `File` 类型与大小判断 |
| `Core/SyncEngine.cs` | 增加 reference 发送/接收逻辑 |
| `Network/HttpClient.cs` | 新增上传下载方法 |
| `Core/*` | 增加临时文件清理组件 |

## 10.2 Android 客户端

### 当前现状

- 大小检查已有部分基础。
- 仍需补上传、下载与引用消息处理。

### 需要实现

- 图片超阈值后上传并发送 `image/reference`
- `file/reference` 下载到缓存目录
- 将缓存文件通过 `content://` URI 暴露给剪贴板
- 退出或过期时清理缓存

---

## 11. 文件生命周期与清理策略

### 11.1 两类文件

| 类型 | 位置 | 生命周期 |
|------|------|----------|
| 服务端上传文件 | 服务端磁盘 | 受服务端保留策略控制 |
| 客户端临时接收文件 | 本地临时目录 | 受客户端清理策略控制 |

### 11.2 服务端保留策略

建议：

- 最近被引用的文件保留至少 7 天。
- 如对应 `clipboard_history` 仍存在最近引用，可延长保留。
- 清理任务删除文件前，先判断是否仍被最近 N 条历史引用。

### 11.3 客户端保留策略

建议：

- 接收后的临时文件保留 24 小时。
- 客户端启动时清理过期临时文件。
- 客户端退出时尽量清理本次会话生成的临时文件。

### 11.4 避免悬挂引用

若历史项存在但服务端文件已清理：

- 下载接口返回 `FILE_NOT_FOUND`
- 客户端 UI 标记为“文件已过期”
- 不应导致同步引擎崩溃

---

## 12. 去重与重试策略

### 12.1 当前风险

现有服务端按 `checksum` 做“历史重复即拒绝”，这对大对象并不合适：

- 用户连续两次复制同一张图是正常行为
- 旧文件被清理后，再次复制同内容也应允许重新上传

### 12.2 推荐策略

- **客户端防抖**：继续使用最近一次 checksum 避免回环。
- **服务端去重**：不要把“历史出现过同 checksum”直接当错误。
- **可选优化**：若近期存在相同 checksum 的 `uploaded_files`，可直接复用已有 `file_id`，而不是拒绝请求。

### 12.3 上传失败重试

- 上传失败时，不发送 reference 消息。
- 可记录一次错误日志并提示用户。
- 不做无限自动重试，避免反复上传大文件。

---

## 13. 阈值与配置

### 13.1 推荐配置

| 配置 | 推荐值 | 说明 |
|------|--------|------|
| `file_reference_threshold_kb` | 512 | 图片超过该阈值走引用模式 |
| `ws_max_message_size_mb` | 1 | WS 允许的最大消息尺寸 |
| `max_file_size_mb` | 50 | HTTP 上传上限 |
| `clipboard_history_limit` | 50 | 每用户历史数量 |

### 13.2 配置注意事项

- `ws_max_message_size_mb` 当前虽然已有配置字段，但服务端 read limit 仍需改成读取配置，而不是硬编码。
- HTTP API 文档中的错误码描述需与 `max_file_size_mb` 统一，不能再保留旧的 `5MB` 文案。

### 13.3 为什么阈值仍选 512KB

- 512KB Base64 后约 683KB，距离 1MB 上限仍有安全余量。
- 大部分普通截图仍可直接走 WS，减少上传链路延迟。
- 4K/多屏截图则自动转入引用模式，避免断连。

---

## 14. 内存与性能评估

### 14.1 2GB 服务器评估

| 组件 | 估算占用 | 说明 |
|------|---------|------|
| Go 运行时 | ~50MB | 基础开销 |
| SQLite + WAL | ~100MB | 缓冲与连接 |
| WebSocket Hub | ~100MB | 100 并发级别 |
| 普通消息队列 | ~50MB | 以文本和小图为主 |
| 可用内存 | ~1.7GB | 较充裕 |

### 14.2 关键收益

大对象走引用模式后：

- 不再经过 WebSocket 大包广播
- 不再写入 SQLite 的大块 Base64
- 不再挤占客户端发送队列
- 数据主体主要落在磁盘，而非内存

### 14.3 瓶颈转移

优化后系统主要瓶颈将从 **内存 / 队列** 转为：

- 上下行带宽
- 磁盘 IO
- 客户端下载完成前的可感知延迟

这是更健康、也更容易控制的瓶颈形态。

---

## 15. 安全性

| 威胁 | 防御措施 | 状态 |
|------|---------|------|
| 恶意超大文件 | HTTP 上限 + WS 上限 | 部分已实现 |
| 路径遍历 | 拒绝 `../` 等非法 file_id | ✅ 已实现 |
| 越权下载 | JWT + 用户归属校验 | ✅ 已实现 |
| 内容篡改 | SHA256 双向校验 | ✅ 已实现 |
| 临时文件泄漏 | 定时清理 + 启动/退出清理 | 待实现 |
| 恶意 MIME | MIME 白名单 / 黑名单策略 | 待实现 |

补充建议：

- 服务端上传时记录 `mime_type`，下载前客户端再次核验。
- 对可执行文件类型可增加风险提示或默认不自动恢复到剪贴板。

---

## 16. 实施计划

### Phase 1：协议与服务端闭环

- [ ] 扩展 `ws-messages.schema.json`，引入 `transfer_mode`
- [ ] 修改 Go 协议结构体
- [ ] 新增 `clipboard_history` migration v2
- [ ] 改造 `handleClipboardPush` 支持 inline/reference
- [ ] 改造 `clipboard_pull` 让历史返回引用元数据

### Phase 2：Windows 大图片支持

- [ ] `ClipboardMonitor` 增加图片阈值判断
- [ ] `HttpClient.UploadFileAsync()` 实现
- [ ] `SyncEngine` 发送 `image/reference`
- [ ] `SyncEngine` 接收 `image/reference` 并恢复图片剪贴板

### Phase 3：Windows 文件剪贴板支持

- [ ] 读取 Windows `FileDropList`
- [ ] 上传系统文件
- [ ] 接收后下载到临时目录
- [ ] 恢复为 Windows 文件剪贴板

### Phase 4：Android 引用模式对齐

- [ ] `uploadFile()` 实现
- [ ] 大图片引用同步
- [ ] 系统文件引用同步
- [ ] 缓存清理

### Phase 5：清理与可观测性

- [ ] 服务端过期文件清理任务
- [ ] 客户端临时文件清理
- [ ] 上传/下载失败日志
- [ ] 可选：传输进度 UI

---

## 17. 验收标准

### 17.1 功能验收

- 1080p 截图可直接实时同步。
- 4K 截图不会导致 WebSocket 断开。
- 大图片可在另一端恢复为图片剪贴板。
- 系统文件可在另一端恢复为可粘贴文件。
- 客户端重连后，最近一次 reference 内容可恢复。

### 17.2 性能验收

- 发送大图片时，小文本同步仍可继续进行。
- SQLite 历史记录不再保存大块 Base64 图片。
- 100 用户量级下，服务端内存稳定。

### 17.3 稳定性验收

- 文件丢失、下载失败、checksum 不匹配时有明确错误处理。
- 历史引用过期时不会导致客户端异常退出。
- 老客户端在兼容窗口内仍可处理 inline 消息。

---

## 18. 备选方案对比

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|-------|
| **A. HTTP 上传 + WS 引用** | 负载分离清晰，最符合当前架构 | 需要补协议和客户端逻辑 | ⭐⭐⭐⭐⭐ |
| B. 直接把 WS 提到 10MB | 改动少 | 队列阻塞、SQLite 膨胀、风险后移 | ⭐⭐ |
| C. WS 分片传输 | 不依赖 HTTP | 复杂度高，重组与重传麻烦 | ⭐⭐⭐ |
| D. 第三方对象存储 | 可扩展性强 | 成本高、外部依赖强 | ⭐⭐⭐ |

**结论**：优先采用方案 A，并以“先大图片、后系统文件”的顺序落地。

---

## 19. 影响文件清单

```text
clipSync-server/
├── configs/config.yaml
├── internal/config/config.go
├── internal/httpserver/upload_handler.go
├── internal/websocket/handler.go
├── internal/websocket/client.go
├── internal/database/migrations.go
├── internal/database/clipboard_repo.go
└── pkg/protocol/messages.go

clipSync-windows/
├── ClipSync.WPF/Network/HttpClient.cs
├── ClipSync.WPF/Core/SyncEngine.cs
├── ClipSync.WPF/Core/ClipboardMonitor.cs
└── ClipSync.WPF/Core/...（临时文件管理与文件剪贴板辅助）

clipSync-android/
├── app/src/main/.../network/ApiClient.kt
├── app/src/main/.../core/SyncEngine.kt
└── app/src/main/.../core/ClipboardMonitor.kt

protocol/
├── ws-messages.schema.json
└── http-api.schema.json
```

---

## 20. 最终建议

这项能力建议拆成两个里程碑：

1. **里程碑一：大图片引用同步**
   - 风险低
   - 能立即解决 1MB WS 限制问题
   - 客户端改动相对集中

2. **里程碑二：系统文件剪贴板同步**
   - 需要更完整的文件剪贴板读写能力
   - 平台差异更大
   - 更适合在大图片方案稳定后推进

这样可以先解决最痛的截图同步问题，再逐步扩展到真正的文件剪贴板场景。
