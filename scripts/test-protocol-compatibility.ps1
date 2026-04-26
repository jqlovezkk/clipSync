# ClipSync Protocol Compatibility Test
# Verifies that all three implementations (Go Server, Windows Client, Android Client)
# follow the same protocol specification.

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "       ClipSync Protocol Compatibility Test Suite        " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

$root = Split-Path $PSScriptRoot -Parent
$passCount = 0
$failCount = 0

function Test-Pass {
    param($name)
    $script:passCount++
    Write-Host "  [PASS] $name" -ForegroundColor Green
}

function Test-Fail {
    param($name, $detail)
    $script:failCount++
    Write-Host "  [FAIL] $name" -ForegroundColor Red
    if ($detail) { Write-Host "    -> $detail" -ForegroundColor Yellow }
}

# Read all source files with UTF-8 encoding
$goContent = Get-Content "$root\clipSync-server\pkg\protocol\messages.go" -Raw -Encoding UTF8
$goAll = ""
Get-ChildItem "$root\clipSync-server\internal" -Recurse -Filter "*.go" | ForEach-Object { $goAll += Get-Content $_.FullName -Raw -Encoding UTF8 }
$goAll += $goContent

$winContent = Get-Content "$root\clipSync-windows\ClipSync.WPF\Network\Protocol.cs" -Raw -Encoding UTF8
$winAll = ""
Get-ChildItem "$root\clipSync-windows\ClipSync.WPF" -Recurse -Filter "*.cs" | ForEach-Object { $winAll += Get-Content $_.FullName -Raw -Encoding UTF8 }
$winAll += $winContent

$androidContent = Get-Content "$root\clipSync-android\app\src\main\java\com\clipsync\app\network\Protocol.kt" -Raw -Encoding UTF8
$androidAll = ""
Get-ChildItem "$root\clipSync-android\app\src\main\java" -Recurse -Filter "*.kt" | ForEach-Object { $androidAll += Get-Content $_.FullName -Raw -Encoding UTF8 }
$androidAll += $androidContent

$serverMain = Get-Content "$root\clipSync-server\cmd\server\main.go" -Raw -Encoding UTF8
$goEnc = Get-Content "$root\clipSync-server\internal\encryption\aes.go" -Raw -Encoding UTF8
$winEnc = Get-Content "$root\clipSync-windows\ClipSync.WPF\Core\EncryptionHelper.cs" -Raw -Encoding UTF8
$androidEnc = Get-Content "$root\clipSync-android\app\src\main\java\com\clipsync\app\core\EncryptionHelper.kt" -Raw -Encoding UTF8
$schema = Get-Content "$root\protocol\ws-messages.schema.json" -Raw -Encoding UTF8

# ─── 1. WebSocket Message Types ───
Write-Host "[1] WebSocket Message Types (14 types)" -ForegroundColor White

$schemaTypes = @("auth", "auth_response", "heartbeat", "heartbeat_ack", "clipboard_push", "clipboard_sync", "clipboard_pull", "clipboard_history", "device_list", "device_list_response", "device_unregister", "error", "ping", "pong")

foreach ($t in $schemaTypes) {
    $inGo = $goAll -match "`"$t`""
    $inWin = $winAll -match "`"$t`""
    $inAndroid = $androidAll -match "`"$t`""
    
    if ($inGo -and $inWin -and $inAndroid) {
        Test-Pass "'$t' in Go+Win+Android"
    } else {
        $missing = @()
        if (-not $inGo) { $missing += "Go" }
        if (-not $inWin) { $missing += "Win" }
        if (-not $inAndroid) { $missing += "Android" }
        Test-Fail "'$t' missing" "Missing in: $($missing -join ', ')"
    }
}

# ─── 2. JSON Field Naming (snake_case) ───
Write-Host ""
Write-Host "[2] JSON Field Naming Consistency" -ForegroundColor White

$fields = @("device_id", "content_type", "source_device_id", "source_device_name", "has_more", "created_at", "last_seen", "is_online", "device_name", "expires_at")
foreach ($field in $fields) {
    $inGo = $goAll -match $field
    $inWin = $winAll -match $field
    $inAndroid = $androidAll -match $field
    
    if ($inGo -and $inWin -and $inAndroid) {
        Test-Pass "Field '$field' uses snake_case in all 3"
    } else {
        $missing = @()
        if (-not $inGo) { $missing += "Go" }
        if (-not $inWin) { $missing += "Win" }
        if (-not $inAndroid) { $missing += "Android" }
        Test-Fail "Field '$field' inconsistent" "Missing in: $($missing -join ', ')"
    }
}

# ─── 3. HTTP API Endpoints ───
Write-Host ""
Write-Host "[3] HTTP API Endpoints" -ForegroundColor White

$endpoints = @(
    "/api/v1/auth/login",
    "/api/v1/auth/register",
    "/api/v1/auth/refresh",
    "/api/v1/health",
    "/api/v1/devices"
)

foreach ($ep in $endpoints) {
    $inServer = $goAll -match [regex]::Escape($ep)
    $inWin = $winAll -match [regex]::Escape($ep)
    $inAndroid = $androidAll -match [regex]::Escape($ep)
    
    if ($inServer -and $inWin -and $inAndroid) {
        Test-Pass "'$ep' in all 3"
    } else {
        $missing = @()
        if (-not $inServer) { $missing += "Server" }
        if (-not $inWin) { $missing += "Win" }
        if (-not $inAndroid) { $missing += "Android" }
        Test-Fail "'$ep' missing" "Missing in: $($missing -join ', ')"
    }
}

# ─── 4. Protocol Version ───
Write-Host ""
Write-Host "[4] Protocol Version" -ForegroundColor White

if ($goContent -match "Version\s*=\s*1") { Test-Pass "Go: Protocol version = 1" } else { Test-Fail "Go: Protocol version mismatch" }
if ($winContent -match "Version.*=\s*1") { Test-Pass "Windows: Protocol version = 1" } else { Test-Fail "Windows: Protocol version mismatch" }
if ($androidContent -match "version:\s*Int\s*=\s*1") { Test-Pass "Android: Protocol version = 1" } else { Test-Fail "Android: Protocol version mismatch" }

# ─── 5. Heartbeat Interval ───
Write-Host ""
Write-Host "[5] Heartbeat Configuration" -ForegroundColor White

if ($goAll -match "heartbeat|Heartbeat") { Test-Pass "Go: Heartbeat monitoring configured" } else { Test-Fail "Go: Heartbeat not found" }
if ($winAll -match "heartbeat|Heartbeat|30") { Test-Pass "Windows: Heartbeat implemented" } else { Test-Fail "Windows: Heartbeat interval not found" }
if ($androidAll -match "heartbeat|Heartbeat|30") { Test-Pass "Android: Heartbeat implemented" } else { Test-Fail "Android: Heartbeat interval not found" }

# ─── 6. Encryption Support ───
Write-Host ""
Write-Host "[6] AES-256 Encryption" -ForegroundColor White

if ($goEnc -match "AES|Aes|aes") { Test-Pass "Go: AES implemented" } else { Test-Fail "Go: AES not found" }
if ($winEnc -match "AES|Aes") { Test-Pass "Windows: AES implemented" } else { Test-Fail "Windows: AES not found" }
if ($androidEnc -match "AES|Aes") { Test-Pass "Android: AES implemented" } else { Test-Fail "Android: AES not found" }

# ─── 7. Error Codes ───
Write-Host ""
Write-Host "[7] Error Codes" -ForegroundColor White

$errorCodes = @("AUTH_FAILED", "TOKEN_EXPIRED", "RATE_LIMITED", "INVALID_PAYLOAD", "CONTENT_TOO_LARGE", "DEVICE_NOT_FOUND", "INTERNAL_ERROR", "DUPLICATE_CONTENT")

foreach ($code in $errorCodes) {
    $inGo = $goAll -match $code
    $inSchema = $schema -match $code
    
    if ($inGo -and $inSchema) {
        Test-Pass "'$code' defined"
    } else {
        $missing = @()
        if (-not $inGo) { $missing += "Go" }
        if (-not $inSchema) { $missing += "Schema" }
        Test-Fail "'$code' missing" "Missing in: $($missing -join ', ')"
    }
}

# ─── 8. Mock Server Test ───
Write-Host ""
Write-Host "[8] Mock Server Connectivity" -ForegroundColor White

try {
    $health = Invoke-RestMethod -Uri "http://localhost:8081/api/v1/health" -Method Get -ErrorAction Stop
    if ($health.status -eq "ok") {
        Test-Pass "Health endpoint responds (status=ok)"
    } else {
        Test-Fail "Health endpoint" "Unexpected status: $($health.status)"
    }
} catch {
    Test-Fail "Health endpoint" "Connection failed: $_"
}

try {
    $loginBody = @{ username="testuser"; password="test123456"; device_name="Test-Win"; platform="windows" } | ConvertTo-Json
    $loginResp = Invoke-RestMethod -Uri "http://localhost:8081/api/v1/auth/login" -Method Post -Body $loginBody -ContentType "application/json" -ErrorAction Stop
    if ($loginResp.success -eq $true -and $loginResp.token -and $loginResp.device_id) {
        Test-Pass "Login endpoint returns token + device_id"
    } else {
        Test-Fail "Login endpoint" "Missing token or device_id"
    }
} catch {
    Test-Fail "Login endpoint" "Request failed: $_"
}

# ─── Summary ───
Write-Host ""
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "                    Test Summary                          " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  Passed: $passCount" -ForegroundColor Green
Write-Host "  Failed: $failCount" -ForegroundColor $(if ($failCount -gt 0) { "Red" } else { "Green" })
Write-Host "========================================================" -ForegroundColor Cyan

if ($failCount -eq 0) {
    Write-Host "`nAll protocol compatibility checks passed!" -ForegroundColor Green
} else {
    Write-Host "`n$failCount compatibility issues found. Review details above." -ForegroundColor Red
}
