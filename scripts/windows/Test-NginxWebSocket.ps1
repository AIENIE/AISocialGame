[CmdletBinding()] param([string]$NginxExe, [string]$EvidenceDirectory)
Set-StrictMode -Version Latest; $ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne 'Win32NT' -or $PSVersionTable.PSVersion.Major -lt 7) { throw 'Requires PowerShell 7 on Windows.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$toolsRoot = Join-Path 'D:\project\aienie\aienie-runtime\windows\toolchains\localappdata' 'aisocialgame/nginx'
$version = '1.31.6'
if (-not $NginxExe) {
    $zip = Join-Path $toolsRoot "nginx-$version.zip"
    $NginxExe = Join-Path $toolsRoot "nginx-$version/nginx.exe"
    if (-not (Test-Path -LiteralPath $NginxExe)) {
        New-Item -ItemType Directory -Path $toolsRoot -Force | Out-Null
        if (-not (Test-Path -LiteralPath $zip)) { Invoke-WebRequest "https://nginx.org/download/nginx-$version.zip" -OutFile $zip -TimeoutSec 300 }
        Expand-Archive -LiteralPath $zip -DestinationPath $toolsRoot -Force
    }
}
$NginxExe = (Resolve-Path -LiteralPath $NginxExe).Path
$node = (Get-Command node.exe -ErrorAction Stop).Source
$runId = [guid]::NewGuid().ToString('N')
$runs = Join-Path $toolsRoot 'runs'
$runRoot = Join-Path $runs $runId
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path 'D:\project\aienie\aienie-runtime\evidence\product-artifacts' "aisocialgame/nginx-ws-$runId" }
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
if ($evidence.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $evidence)) { throw 'Use a new evidence directory outside the checkout.' }
New-Item -ItemType Directory -Path $evidence, (Join-Path $runRoot 'conf'), (Join-Path $runRoot 'logs'), (Join-Path $runRoot 'temp') -Force | Out-Null
function Free-Port {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); return ([Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}
$upstreamPort = Free-Port
$proxyPort = Free-Port
$server = Get-Content (Join-Path $root 'frontend/nginx.conf') -Raw
if (-not $server.Contains('location = /ws') -or -not $server.Contains('location /ws/')) { throw 'Repository WebSocket locations are missing.' }
$server = $server.Replace('listen 11030;', "listen 127.0.0.1:$proxyPort;").Replace('http://backend:20030', "http://127.0.0.1:$upstreamPort")
"worker_processes 1; events { worker_connections 128; } http { $server }" | Set-Content (Join-Path $runRoot 'conf/nginx.conf')
$prefix = $runRoot.Replace('\', '/') + '/'
$args = "-p `"$prefix`" -c conf/nginx.conf"
$upstream = $null; $proxy = $null; $passed = $false
try {
    & $NginxExe -p $prefix -c conf/nginx.conf -t *> (Join-Path $evidence 'nginx-test.log')
    if ($LASTEXITCODE) { throw 'Nginx configuration test failed.' }
    $upstream = Start-Process -FilePath $node -ArgumentList @("`"$(Join-Path $PSScriptRoot 'support/nginx-ws-upstream.mjs')`"", [string]$upstreamPort) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence 'upstream.log') -RedirectStandardError (Join-Path $evidence 'upstream-error.log')
    $proxy = Start-Process -FilePath $NginxExe -ArgumentList $args -WorkingDirectory $runRoot -WindowStyle Hidden -PassThru
    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        try { if ((Invoke-WebRequest "http://127.0.0.1:$proxyPort/healthz" -TimeoutSec 1).StatusCode -eq 200) { $ready = $true; break } } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $ready) { throw 'Nginx did not become ready.' }
    foreach ($path in @('/ws', '/ws/info')) {
        $reply = Invoke-WebRequest "http://127.0.0.1:$proxyPort$path" -MaximumRedirection 0 -TimeoutSec 5
        if ($reply.StatusCode -ne 200) { throw "Unexpected HTTP response for $path" }
    }
    foreach ($path in @('/ws', '/ws/123/abc/websocket')) {
        $socket = [Net.WebSockets.ClientWebSocket]::new()
        try {
            $socket.ConnectAsync([uri]"ws://127.0.0.1:$proxyPort$path", [Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null
            if ($socket.State -ne [Net.WebSockets.WebSocketState]::Open) { throw "WebSocket upgrade failed for $path" }
        } finally { $socket.Dispose() }
    }
    Start-Sleep -Milliseconds 300
    $requests = Get-Content (Join-Path $evidence 'upstream.log') -Raw
    foreach ($line in @('HTTP /ws', 'HTTP /ws/info', 'UPGRADE /ws', 'UPGRADE /ws/123/abc/websocket')) {
        if (-not $requests.Contains($line)) { throw "Missing upstream request: $line" }
    }
    @{ passed = $true; nginxVersion = $version; nativeUpgrade = $true; sockJsUpgrade = $true; exactWsNoRedirect = $true; upstreamRequests = $requests.Trim().Split("`n") } | ConvertTo-Json | Set-Content (Join-Path $evidence 'result.json')
    $passed = $true
} finally {
    try { & $NginxExe -p $prefix -c conf/nginx.conf -s quit *> $null } catch {}
    if ($upstream -and -not $upstream.HasExited) { $upstream.Kill(); $upstream.WaitForExit() }
    if ($proxy -and -not $proxy.HasExited) { $proxy.WaitForExit(5000) | Out-Null }
    $resolved = [IO.Path]::GetFullPath($runRoot)
    $parent = [IO.Path]::GetFullPath($runs) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($parent, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -cne $runId) { throw 'Unsafe temporary cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
if (-not $passed) { throw 'Nginx WebSocket test failed.' }
Write-Host "PASS nginx WebSocket proxy; evidence: $evidence"
