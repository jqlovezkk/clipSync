$ADB = "C:\Users\20562\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$PACKAGE = "com.clipsync.app"
$LOG_DIR = "C:\Users\20562\Desktop\桌面\clipSync\logs"
$TODAY = Get-Date -Format "yyyy-MM-dd"
$REMOTE = "files/logs/clipsync_android_${TODAY}.log"
$LOCAL  = Join-Path $LOG_DIR "clipsync_android_${TODAY}.log"
$TMP    = "$env:TEMP\clipsync_android_tmp.log"

if (-not (Test-Path $LOG_DIR)) { New-Item -ItemType Directory -Path $LOG_DIR | Out-Null }

Write-Host "===================================="
Write-Host "ClipSync Android Live Log Sync"
Write-Host "===================================="
Write-Host "Package: $PACKAGE"
Write-Host "Remote:  $REMOTE"
Write-Host "Local:   $LOCAL"
Write-Host "Interval: 1s"
Write-Host ""
Write-Host "Press Ctrl+C to stop"
Write-Host "===================================="
Write-Host ""

$LAST_LINES = 0
$FIRST = $true

while ($true) {
    try {
        # Pull file to temp
        $pullResult = & $ADB shell run-as $PACKAGE cat "$REMOTE" 2>$null
        if ($null -eq $pullResult -or $pullResult.Count -eq 0) {
            Start-Sleep -Seconds 2
            continue
        }

        $totalLines = $pullResult.Count

        if ($FIRST) {
            $pullResult | Out-File -FilePath $LOCAL -Encoding UTF8
            Write-Host "[$(Get-Date -Format HH:mm:ss)] Initial sync: $totalLines lines"
            $LAST_LINES = $totalLines
            $FIRST = $false
        }
        elseif ($totalLines -gt $LAST_LINES) {
            $newLines = $totalLines - $LAST_LINES
            $pullResult | Select-Object -Last $newLines | Add-Content -Path $LOCAL -Encoding UTF8
            Write-Host "[$(Get-Date -Format HH:mm:ss)] +${newLines} lines (total: $totalLines)"
            $LAST_LINES = $totalLines
        }
    }
    catch { Write-Host "[$(Get-Date -Format HH:mm:ss)] Err: $_" }

    Start-Sleep -Seconds 1
}
