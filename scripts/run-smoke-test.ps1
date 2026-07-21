param(
    [string]$TestRoot,
    [int]$StartupTimeoutSeconds = 300,
    [int]$ShutdownTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
if (-not $TestRoot) {
    $TestRoot = Join-Path $workspace 'work\worldgriddeployer-smoketest'
}
$TestRoot = (Resolve-Path -LiteralPath $TestRoot).Path

$java = Join-Path $workspace 'runtime\temurin-21\jdk-21.0.11+10-jre\bin\java.exe'
$latestLog = Join-Path $TestRoot 'logs\latest.log'
$stdoutPath = Join-Path $TestRoot 'smoke-console.log'
$stderrPath = Join-Path $TestRoot 'smoke-console.err.log'
$baselineWrite = if (Test-Path -LiteralPath $latestLog) {
    (Get-Item -LiteralPath $latestLog).LastWriteTimeUtc
} else {
    [datetime]::MinValue
}

$info = [System.Diagnostics.ProcessStartInfo]::new()
$info.FileName = $java
$info.Arguments = '@user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.235/win_args.txt nogui'
$info.WorkingDirectory = $TestRoot
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true

@($info.Environment.Keys | Where-Object { $_ -ieq 'PATH' }) | ForEach-Object {
    $info.Environment.Remove($_) | Out-Null
}
$info.Environment['PATH'] = "$(Split-Path -Parent $java);C:\Windows\System32;C:\Windows"

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $info
if (-not $process.Start()) {
    throw 'Smoke-test server process did not start.'
}

Write-Host "SMOKE_PID=$($process.Id)"
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$ready = $false
$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)

while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
    if (Test-Path -LiteralPath $latestLog) {
        $log = Get-Item -LiteralPath $latestLog
        if ($log.LastWriteTimeUtc -gt $baselineWrite) {
            if (Select-String -LiteralPath $latestLog -Pattern 'Done \([0-9.,]+s\)! For help, type' -Quiet) {
                $ready = $true
                break
            }
        }
    }
    Start-Sleep -Seconds 1
}

if ($ready) {
    Write-Host 'SERVER_READY=1'
    Start-Sleep -Seconds 2
} else {
    Write-Host 'SERVER_READY=0'
}

if (-not $process.HasExited) {
    $process.StandardInput.WriteLine('stop')
    $process.StandardInput.Flush()
    $process.StandardInput.Close()
}

if (-not $process.WaitForExit($ShutdownTimeoutSeconds * 1000)) {
    Write-Host 'CLEAN_SHUTDOWN_TIMEOUT=1'
    $process.Kill()
    $process.WaitForExit()
}

$stdoutTask.GetAwaiter().GetResult() | Set-Content -LiteralPath $stdoutPath -Encoding utf8
$stderrTask.GetAwaiter().GetResult() | Set-Content -LiteralPath $stderrPath -Encoding utf8

Write-Host "EXIT_CODE=$($process.ExitCode)"
Write-Host "LATEST_LOG=$latestLog"
Write-Host "STDOUT_LOG=$stdoutPath"
Write-Host "STDERR_LOG=$stderrPath"

if (-not $ready) {
    exit 2
}
if ($process.ExitCode -ne 0) {
    exit $process.ExitCode
}
