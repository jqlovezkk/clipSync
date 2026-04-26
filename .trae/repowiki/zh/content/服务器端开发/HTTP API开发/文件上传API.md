# 文件上传API

<cite>
**本文档引用的文件**
- [upload_handler.go](file://clipSync-server/internal/httpserver/upload_handler.go)
- [main.go](file://clipSync-server/cmd/server/main.go)
- [config.yaml](file://clipSync-server/configs/config.yaml)
- [models.go](file://clipSync-server/internal/database/models.go)
- [migrations.go](file://clipSync-server/internal/database/migrations.go)
- [middleware.go](file://clipSync-server/internal/auth/middleware.go)
- [http-api.schema.json](file://protocol/http-api.schema.json)
- [ApiClient.kt](file://clipSync-android/app/src/main/java/com/clipsync/app/network/ApiClient.kt)
</cite>

## 目录
1. [简介](#简介)
2. [项目结构](#项目结构)
3. [核心组件](#核心组件)
4. [架构概览](#架构概览)
5. [详细组件分析](#详细组件分析)
6. [依赖关系分析](#依赖关系分析)
7. [性能考虑](#性能考虑)
8. [故障排除指南](#故障排除指南)
9. [结论](#结论)
10. [附录](#附录)

## 简介
本文档详细说明了ClipSync服务器的文件上传API实现，包括POST /api/v1/upload和GET /api/v1/download/{file_id}两个核心接口。该API支持多部分表单数据上传、文件大小限制、校验和验证、用户隔离存储等特性，并与剪贴板同步功能紧密集成。

## 项目结构
文件上传功能位于服务器端的HTTP服务器模块中，采用分层架构设计：

```mermaid
graph TB
subgraph "服务器端架构"
A[HTTP路由层] --> B[认证中间件]
B --> C[上传处理器]
C --> D[数据库层]
C --> E[文件系统存储]
subgraph "配置层"
F[配置文件]
G[JWT配置]
end
F --> A
G --> B
end
subgraph "客户端集成"
H[Android客户端]
I[Windows客户端]
J[其他平台客户端]
end
H --> A
I --> A
J --> A
```

**图表来源**
- [main.go:95-98](file://clipSync-server/cmd/server/main.go#L95-L98)
- [upload_handler.go:19-34](file://clipSync-server/internal/httpserver/upload_handler.go#L19-L34)

**章节来源**
- [main.go:95-98](file://clipSync-server/cmd/server/main.go#L95-L98)
- [config.yaml:18-22](file://clipSync-server/configs/config.yaml#L18-L22)

## 核心组件
文件上传API由以下核心组件构成：

### 1. 上传处理器 (UploadHandler)
负责处理文件上传和下载请求，实现安全的文件存储和访问控制。

### 2. 认证中间件
确保所有文件操作都经过有效的JWT令牌验证。

### 3. 数据库模型
定义文件元数据存储结构，包括文件ID、用户关联、校验和等信息。

### 4. 配置管理
通过YAML配置文件管理存储路径、文件大小限制等参数。

**章节来源**
- [upload_handler.go:19-34](file://clipSync-server/internal/httpserver/upload_handler.go#L19-L34)
- [models.go:35-45](file://clipSync-server/internal/database/models.go#L35-L45)
- [config.yaml:18-22](file://clipSync-server/configs/config.yaml#L18-L22)

## 架构概览
文件上传系统的整体架构如下：

```mermaid
sequenceDiagram
participant Client as 客户端应用
participant Auth as 认证中间件
participant Handler as 上传处理器
participant DB as 数据库
participant FS as 文件系统
Client->>Auth : Bearer Token
Auth->>Auth : 验证JWT令牌
Auth->>Handler : 转发已认证请求
alt 上传文件
Client->>Handler : POST /api/v1/upload (multipart/form-data)
Handler->>Handler : 解析multipart表单
Handler->>FS : 创建用户目录
Handler->>FS : 写入文件并计算校验和
Handler->>DB : 记录文件元数据
Handler-->>Client : 返回file_id和download_url
else 下载文件
Client->>Handler : GET /api/v1/download/{file_id}
Handler->>DB : 验证文件归属
Handler->>FS : 检查文件存在性
Handler-->>Client : 返回文件内容
end
```

**图表来源**
- [upload_handler.go:36-150](file://clipSync-server/internal/httpserver/upload_handler.go#L36-L150)
- [upload_handler.go:152-214](file://clipSync-server/internal/httpserver/upload_handler.go#L152-L214)

## 详细组件分析

### 上传处理器 (UploadHandler)
上传处理器是文件上传功能的核心实现，包含以下关键特性：

#### 数据结构设计
```mermaid
classDiagram
class UploadHandler {
-db : sql.DB
-storagePath : string
-maxFileSize : int64
+Upload(w, r)
+Download(w, r)
+NewUploadHandler(db, storagePath, maxFileSizeMB)
}
class UploadedFile {
+id : string
+user_id : int64
+filename : string
+mime_type : string
+size : int64
+checksum : string
+file_path : string
+created_at : int64
}
UploadHandler --> UploadedFile : "存储元数据"
```

**图表来源**
- [upload_handler.go:20-24](file://clipSync-server/internal/httpserver/upload_handler.go#L20-L24)
- [models.go:35-45](file://clipSync-server/internal/database/models.go#L35-L45)

#### 上传流程详解
```mermaid
flowchart TD
Start([开始上传]) --> MethodCheck{检查HTTP方法}
MethodCheck --> |POST| AuthCheck{验证JWT令牌}
MethodCheck --> |其他| MethodError[返回405错误]
AuthCheck --> |失败| AuthError[返回401错误]
AuthCheck --> |成功| SizeLimit[设置大小限制]
SizeLimit --> ParseForm{解析multipart表单}
ParseForm --> |失败| TooLarge[返回413错误]
ParseForm --> |成功| GetFile[获取文件流]
GetFile --> ComputeHash[同时写入文件并计算SHA256]
ComputeHash --> VerifyChecksum{验证客户端校验和}
VerifyChecksum --> |不匹配| ChecksumError[返回400错误]
VerifyChecksum --> |匹配| StoreMeta[存储文件元数据]
StoreMeta --> Success[返回200成功响应]
```

**图表来源**
- [upload_handler.go:36-150](file://clipSync-server/internal/httpserver/upload_handler.go#L36-L150)

#### 下载流程详解
```mermaid
flowchart TD
Start([开始下载]) --> MethodCheck{检查HTTP方法}
MethodCheck --> |GET| AuthCheck{验证JWT令牌}
MethodCheck --> |其他| MethodError[返回405错误]
AuthCheck --> |失败| AuthError[返回401错误]
AuthCheck --> |成功| ExtractID[提取file_id]
ExtractID --> ValidateID{验证文件ID安全性}
ValidateID --> |无效| InvalidID[返回400错误]
ValidateID --> |有效| CheckOwnership[检查文件归属]
CheckOwnership --> |不属于当前用户| AccessDenied[返回403错误]
CheckOwnership --> |属于当前用户| CheckExists[检查文件是否存在]
CheckExists --> |不存在| NotFound[返回404错误]
CheckExists --> |存在| ServeFile[返回文件内容]
```

**图表来源**
- [upload_handler.go:152-214](file://clipSync-server/internal/httpserver/upload_handler.go#L152-L214)

**章节来源**
- [upload_handler.go:36-220](file://clipSync-server/internal/httpserver/upload_handler.go#L36-L220)

### 认证与授权机制
系统采用JWT令牌进行身份验证，确保只有合法用户才能访问文件上传和下载功能：

```mermaid
sequenceDiagram
participant Client as 客户端
participant Middleware as 认证中间件
participant JWT as JWT管理器
participant Handler as 处理器
Client->>Middleware : Authorization : Bearer <token>
Middleware->>JWT : 验证令牌有效性
JWT-->>Middleware : 返回用户声明
Middleware->>Handler : 将用户ID注入上下文
Handler->>Handler : 执行业务逻辑
Handler-->>Client : 返回结果
```

**图表来源**
- [middleware.go:32-61](file://clipSync-server/internal/auth/middleware.go#L32-L61)

**章节来源**
- [middleware.go:32-61](file://clipSync-server/internal/auth/middleware.go#L32-L61)

### 数据库设计
文件元数据存储在SQLite数据库中，采用用户隔离的存储策略：

```mermaid
erDiagram
USERS {
integer id PK
string username UK
string password_hash
integer created_at
}
UPLOADED_FILES {
string id PK
integer user_id FK
string filename
string mime_type
integer size
string checksum
string file_path
integer created_at
}
USERS ||--o{ UPLOADED_FILES : "拥有"
```

**图表来源**
- [migrations.go:65-77](file://clipSync-server/internal/database/migrations.go#L65-L77)

**章节来源**
- [migrations.go:65-77](file://clipSync-server/internal/database/migrations.go#L65-L77)
- [models.go:35-45](file://clipSync-server/internal/database/models.go#L35-L45)

## 依赖关系分析

### 组件依赖图
```mermaid
graph TB
subgraph "外部依赖"
A[net/http]
B[crypto/sha256]
C[encoding/hex]
D[database/sql]
E[os/path/filepath]
end
subgraph "内部模块"
F[auth middleware]
G[database models]
H[httpserver handlers]
I[config management]
end
H --> F
H --> G
H --> A
H --> B
H --> D
H --> E
I --> H
F --> A
G --> D
```

**图表来源**
- [upload_handler.go:3-17](file://clipSync-server/internal/httpserver/upload_handler.go#L3-L17)
- [main.go:3-16](file://clipSync-server/cmd/server/main.go#L3-L16)

### 关键依赖关系
- **认证依赖**: 上传处理器依赖认证中间件进行JWT验证
- **存储依赖**: 使用标准库的文件系统操作进行本地存储
- **数据库依赖**: 通过SQL接口管理文件元数据
- **配置依赖**: 从YAML配置文件读取运行时参数

**章节来源**
- [upload_handler.go:3-17](file://clipSync-server/internal/httpserver/upload_handler.go#L3-L17)
- [main.go:3-16](file://clipSync-server/cmd/server/main.go#L3-L16)

## 性能考虑
文件上传API在设计时充分考虑了性能和可扩展性：

### 存储策略优化
- **用户隔离**: 每个用户拥有独立的存储目录，避免文件冲突
- **流式处理**: 使用io.Copy进行内存友好的大文件传输
- **并发安全**: 通过文件锁和原子操作保证数据一致性

### 安全性保障
- **路径遍历防护**: 严格验证文件ID，防止目录遍历攻击
- **大小限制**: 通过MaxBytesReader限制请求体大小
- **校验和验证**: 双重校验确保文件完整性

### 错误恢复机制
- **事务回滚**: 数据库操作在失败时自动回滚
- **文件清理**: 异常情况下自动删除临时文件
- **幂等性**: 支持重复上传但不产生重复记录

## 故障排除指南

### 常见错误及解决方案

#### 1. 认证失败 (401 Unauthorized)
**症状**: 返回AUTH_FAILED或TOKEN_EXPIRED错误
**原因**: 
- 缺少Authorization头
- JWT令牌格式不正确
- 令牌已过期或无效

**解决方案**:
- 确保请求包含正确的Bearer Token格式
- 检查令牌有效期
- 重新登录获取新令牌

#### 2. 文件过大 (413 Request Entity Too Large)
**症状**: 返回CONTENT_TOO_LARGE错误
**原因**: 文件大小超过配置限制
**解决方案**:
- 检查配置文件中的max_file_size_mb设置
- 分割大文件或调整配置

#### 3. 文件不存在 (404 Not Found)
**症状**: 返回FILE_NOT_FOUND错误
**原因**:
- 文件ID无效或已被删除
- 用户无权访问该文件

**解决方案**:
- 验证文件ID的正确性
- 确认文件仍在服务器上

#### 4. 校验和不匹配 (400 Bad Request)
**症状**: 返回CHECKSUM_MISMATCH错误
**原因**: 上传过程中文件损坏或被篡改
**解决方案**:
- 重新上传文件
- 检查网络连接稳定性

**章节来源**
- [upload_handler.go:43-50](file://clipSync-server/internal/httpserver/upload_handler.go#L43-L50)
- [upload_handler.go:55-61](file://clipSync-server/internal/httpserver/upload_handler.go#L55-L61)
- [upload_handler.go:115-123](file://clipSync-server/internal/httpserver/upload_handler.go#L115-L123)

## 结论
ClipSync的文件上传API实现了安全、可靠且高效的文件传输功能。通过JWT认证、文件大小限制、校验和验证和用户隔离存储等机制，确保了系统的安全性、稳定性和可扩展性。该API与剪贴板同步功能无缝集成，为用户提供了一致的跨设备体验。

## 附录

### API规范详情

#### POST /api/v1/upload
**请求格式**:
- 方法: POST
- 头部: Authorization: Bearer <token>
- 内容类型: multipart/form-data
- 表单字段:
  - file: 二进制文件内容
  - checksum: SHA256校验和（可选）

**响应格式**:
```json
{
  "success": true,
  "file_id": "string",
  "download_url": "string"
}
```

#### GET /api/v1/download/{file_id}
**请求格式**:
- 方法: GET
- 头部: Authorization: Bearer <token>
- 路径参数: file_id

**响应格式**:
- 成功: 文件二进制内容
- 失败: JSON错误对象

**章节来源**
- [http-api.schema.json:211-278](file://protocol/http-api.schema.json#L211-L278)

### 配置参数说明
- **file_storage_path**: 文件存储根目录，默认./data/files
- **max_file_size_mb**: 最大文件大小限制，默认5MB
- **jwt_expiry_hours**: JWT令牌有效期，默认720小时

**章节来源**
- [config.yaml:18-22](file://clipSync-server/configs/config.yaml#L18-L22)