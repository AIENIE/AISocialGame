function ConvertTo-LocalProcessArgument {
    [CmdletBinding()]
    param([AllowEmptyString()][Parameter(Mandatory)][string]$Value)

    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }
    $builder = [Text.StringBuilder]::new()
    [void]$builder.Append('"')
    $backslashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $backslashes++
            continue
        }
        if ($character -eq '"') {
            [void]$builder.Append(('\' * (($backslashes * 2) + 1)))
            [void]$builder.Append('"')
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) {
            [void]$builder.Append(('\' * $backslashes))
            $backslashes = 0
        }
        [void]$builder.Append($character)
    }
    if ($backslashes -gt 0) {
        [void]$builder.Append(('\' * ($backslashes * 2)))
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-LocalScript {
    param([string]$Path, [hashtable]$Parameters = @{})
    $arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', $Path)
    foreach ($entry in $Parameters.GetEnumerator()) {
        if ($entry.Value -is [bool] -or $entry.Value -is [switch]) {
            if ($entry.Value) { $arguments += "-$($entry.Key)" }
        } elseif ($null -ne $entry.Value -and [string]$entry.Value -ne '') {
            $arguments += @("-$($entry.Key)", [string]$entry.Value)
        }
    }
    if ((Split-Path $Path -Leaf) -eq 'Start-Local.ps1') {
        # Wait for the launcher PID, not the long-lived services it creates.
        # A native PowerShell pipeline can retain descendant console handles.
        $token = [Guid]::NewGuid().ToString('N')
        $stdout = Join-Path ([IO.Path]::GetTempPath()) "aienie-start-$token.stdout.log"
        $stderr = Join-Path ([IO.Path]::GetTempPath()) "aienie-start-$token.stderr.log"
        $line = ($arguments | ForEach-Object { ConvertTo-LocalProcessArgument ([string]$_) }) -join ' '
        $process = Start-Process -FilePath (Get-Command pwsh.exe -ErrorAction Stop).Source -ArgumentList $line -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        try {
            $process.WaitForExit()
            $result = $process.ExitCode
            foreach ($log in @($stdout, $stderr)) { if (Test-Path $log) { Get-Content $log | Out-Host } }
            if ($result -ne 0) { throw "$Path failed with exit code $result." }
        } finally {
            $process.Dispose()
            Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
        }
        return
    }
    & (Get-Command pwsh.exe -ErrorAction Stop).Source @arguments
    if ($LASTEXITCODE -ne 0) { throw "$Path failed with exit code $LASTEXITCODE." }
}
function Assert-LocalEndpoint {
    param([string]$Uri, [switch]$Health, [switch]$Json)
    $ErrorActionPreference = 'Stop'
    $headers = if ($Health -or $Json) { @{Accept='application/json'} } else { @{} }
    $response = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try { $response = Invoke-WebRequest -Uri $Uri -Headers $headers -TimeoutSec 30; break }
        catch { if ($attempt -eq 3) { throw }; Start-Sleep -Seconds 2 }
    }
    if ($response.StatusCode -ne 200) { throw "Endpoint failed: $Uri" }
    $content = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
    if ($Health -and ($content | ConvertFrom-Json -ErrorAction Stop).status -ne 'UP') { throw "Service is not UP: $Uri" }
    if ($Json) { $content | ConvertFrom-Json -ErrorAction Stop | Out-Null }
    Write-Host "PASS $Uri"
}


function Get-LocalRecordedIds {
    param([string]$StateFile)
    if (-not (Test-Path -LiteralPath $StateFile)) { return }
    $state = Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json
    foreach ($record in @($state.processes)) {
        if ($record.PSObject.Properties['RootProcess']) { [int]$record.RootProcess.ProcessId }
        else { [int]$record.ProcessId }
    }
}

function Assert-LocalDebugListener {
    param([int]$HttpPort, [int]$DebugPort)
    $owners = @(Get-NetTCPConnection -State Listen -LocalPort $HttpPort -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess)
    $listeners = @(Get-NetTCPConnection -State Listen,Established -LocalPort $DebugPort -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -eq '127.0.0.1' -and $_.OwningProcess -in $owners })
    if (-not $listeners.Count) { throw "Backend on $HttpPort does not expose loopback JDWP on $DebugPort. Stop that component explicitly, then restart with -EnableBackendDebug." }
}
