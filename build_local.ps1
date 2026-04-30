$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )

    Write-Host "== $Message ==" -ForegroundColor Cyan
    & $Action
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Command,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (exit code: $LASTEXITCODE)"
    }
}

function Import-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path $Path)) {
        return
    }

    Write-Host "== Load env file: $([IO.Path]::GetFileName($Path)) ==" -ForegroundColor Cyan

    foreach ($rawLine in (Get-Content -Path $Path -ErrorAction Stop)) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
            continue
        }

        $match = [regex]::Match($line, '^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$')
        if (-not $match.Success) {
            continue
        }

        $name = $match.Groups[1].Value
        $value = $match.Groups[2].Value.Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            if ($value.Length -ge 2) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

function Import-ProjectEnv {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $envTxtPath = Join-Path $RepoRoot "env.txt"

    if (Test-Path $envTxtPath) {
        Import-EnvFile -Path $envTxtPath
    }
}

function Wait-Http {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$Attempts = 90,
        [int]$DelaySeconds = 2
    )

    for ($i = 0; $i -lt $Attempts; $i++) {
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 | Out-Null
            return
        }
        catch {
            Start-Sleep -Seconds $DelaySeconds
        }
    }

    throw "Service $Url not ready after $Attempts attempts."
}

function Stop-ProcessByPidFile {
    param([Parameter(Mandatory = $true)][string]$PidFile)

    if (-not (Test-Path $PidFile)) {
        return
    }

    $pidText = (Get-Content -Path $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($pidText -and ($pidText -match '^\d+$')) {
        $pidValue = [int]$pidText
        $existing = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
        if ($existing) {
            Stop-Process -Id $pidValue -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 1
        }
    }

    Remove-Item -Path $PidFile -Force -ErrorAction SilentlyContinue
}

function Stop-PortOwner {
    param([Parameter(Mandatory = $true)][int]$Port)

    $owners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($owner in $owners) {
        if ($owner -and $owner -ne 0) {
            Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue
        }
    }
}

function Set-DefaultEnv {
    if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_URL)) {
        $mysqlHost = if ([string]::IsNullOrWhiteSpace($env:MYSQL_HOST)) { '192.168.5.208' } else { $env:MYSQL_HOST }
        $mysqlPort = if ([string]::IsNullOrWhiteSpace($env:MYSQL_PORT)) { '3306' } else { $env:MYSQL_PORT }
        $mysqlDb = if ([string]::IsNullOrWhiteSpace($env:MYSQL_DB)) { 'aisocialgame' } else { $env:MYSQL_DB }
        $env:SPRING_DATASOURCE_URL = "jdbc:mysql://$mysqlHost`:$mysqlPort/${mysqlDb}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    }
    if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_USERNAME)) { $env:SPRING_DATASOURCE_USERNAME = "aisocialgame" }
    if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_PASSWORD)) { $env:SPRING_DATASOURCE_PASSWORD = "aisocialgame_pwd" }
    if ([string]::IsNullOrWhiteSpace($env:SPRING_DATA_REDIS_HOST)) { $env:SPRING_DATA_REDIS_HOST = "192.168.5.208" }
    if ([string]::IsNullOrWhiteSpace($env:SPRING_DATA_REDIS_PORT)) { $env:SPRING_DATA_REDIS_PORT = "6379" }
    if ([string]::IsNullOrWhiteSpace($env:CONSUL_HTTP_ADDR)) { $env:CONSUL_HTTP_ADDR = "http://192.168.5.208:60000" }
    if ([string]::IsNullOrWhiteSpace($env:QDRANT_HOST)) { $env:QDRANT_HOST = "http://192.168.5.208" }
    if ([string]::IsNullOrWhiteSpace($env:QDRANT_PORT)) { $env:QDRANT_PORT = "6333" }
    if ([string]::IsNullOrWhiteSpace($env:QDRANT_ENABLED)) { $env:QDRANT_ENABLED = "true" }
    if ([string]::IsNullOrWhiteSpace($env:USER_GRPC_SERVICE_NAME)) { $env:USER_GRPC_SERVICE_NAME = "aienie-userservice-grpc" }
    if ([string]::IsNullOrWhiteSpace($env:BILLING_GRPC_SERVICE_NAME)) { $env:BILLING_GRPC_SERVICE_NAME = "aienie-payservice-grpc" }
    if ([string]::IsNullOrWhiteSpace($env:AI_GRPC_SERVICE_NAME)) { $env:AI_GRPC_SERVICE_NAME = "aienie-aiservice-grpc" }
    if ([string]::IsNullOrWhiteSpace($env:USER_GRPC_ADDR)) { $env:USER_GRPC_ADDR = "consul:///$($env:USER_GRPC_SERVICE_NAME)" }
    if ([string]::IsNullOrWhiteSpace($env:BILLING_GRPC_ADDR)) { $env:BILLING_GRPC_ADDR = "consul:///$($env:BILLING_GRPC_SERVICE_NAME)" }
    if ([string]::IsNullOrWhiteSpace($env:AI_GRPC_ADDR)) { $env:AI_GRPC_ADDR = "consul:///$($env:AI_GRPC_SERVICE_NAME)" }
    if ([string]::IsNullOrWhiteSpace($env:SSO_USER_SERVICE_NAME)) { $env:SSO_USER_SERVICE_NAME = "aienie-userservice-http" }
    if ([string]::IsNullOrWhiteSpace($env:SSO_USER_SERVICE_BASE_URL)) { $env:SSO_USER_SERVICE_BASE_URL = "https://userservice.seekerhut.com" }
    if ([string]::IsNullOrWhiteSpace($env:SSO_CALLBACK_URL)) { $env:SSO_CALLBACK_URL = "https://aisocialgame.seekerhut.com/sso/callback" }
    if ([string]::IsNullOrWhiteSpace($env:SSO_LOGIN_PATH)) { $env:SSO_LOGIN_PATH = "/sso/login" }
    if ([string]::IsNullOrWhiteSpace($env:SSO_REGISTER_PATH)) { $env:SSO_REGISTER_PATH = "/register" }
    if ([string]::IsNullOrWhiteSpace($env:USER_SERVICE_BASE_URL)) { $env:USER_SERVICE_BASE_URL = "https://userservice.seekerhut.com" }
    if ([string]::IsNullOrWhiteSpace($env:PAY_SERVICE_BASE_URL)) { $env:PAY_SERVICE_BASE_URL = "https://payservice.seekerhut.com" }
    if ([string]::IsNullOrWhiteSpace($env:AI_SERVICE_BASE_URL)) { $env:AI_SERVICE_BASE_URL = "https://aiservice.seekerhut.com" }
    if ([string]::IsNullOrWhiteSpace($env:APP_EXTERNAL_GRPC_AUTH_REQUIRED)) { $env:APP_EXTERNAL_GRPC_AUTH_REQUIRED = "true" }
}

function Assert-RequiredEnv {
    param([Parameter(Mandatory = $true)][string[]]$Names)

    $missing = @()
    foreach ($name in $Names) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            $missing += $name
        }
    }
    if ($missing.Count -gt 0) {
        throw "Missing required environment variables: $($missing -join ', ')"
    }
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

Import-ProjectEnv -RepoRoot $repoRoot
if ([string]::IsNullOrWhiteSpace($env:CI)) { $env:CI = "true" }
$frontendPort = if ([string]::IsNullOrWhiteSpace($env:FRONTEND_PORT)) { 11030 } else { [int]$env:FRONTEND_PORT }
$backendPort = if ([string]::IsNullOrWhiteSpace($env:BACKEND_PORT)) { 11031 } else { [int]$env:BACKEND_PORT }

$logDir = Join-Path $repoRoot "artifacts/local-run"
$backendPidFile = Join-Path $logDir "backend.pid"
$frontendPidFile = Join-Path $logDir "frontend.pid"
$backendStdout = Join-Path $logDir "backend.stdout.log"
$backendStderr = Join-Path $logDir "backend.stderr.log"
$frontendStdout = Join-Path $logDir "frontend.stdout.log"
$frontendStderr = Join-Path $logDir "frontend.stderr.log"

New-Item -Path $logDir -ItemType Directory -Force | Out-Null
Set-DefaultEnv
if ($env:APP_EXTERNAL_GRPC_AUTH_REQUIRED -eq "true") {
    Assert-RequiredEnv -Names @(
        "APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN",
        "APP_EXTERNAL_PAYSERVICE_JWT",
        "APP_EXTERNAL_AISERVICE_HMAC_CALLER",
        "APP_EXTERNAL_AISERVICE_HMAC_SECRET"
    )
}
$env:SERVER_PORT = "$backendPort"

Invoke-Step -Message "Stop previous local services" -Action {
    Stop-ProcessByPidFile -PidFile $backendPidFile
    Stop-ProcessByPidFile -PidFile $frontendPidFile
    Stop-PortOwner -Port $backendPort
    Stop-PortOwner -Port $frontendPort
}

Invoke-Step -Message "Backend: package" -Action {
    Push-Location "$repoRoot/backend"
    try {
        Invoke-Native -FailureMessage "Backend package failed" -Command {
            mvn clean package -DskipTests
        }
    }
    finally {
        Pop-Location
    }
}

Invoke-Step -Message "Frontend: install & build" -Action {
    Push-Location "$repoRoot/frontend"
    try {
        corepack enable *> $null
        Invoke-Native -FailureMessage "Frontend install failed" -Command {
            pnpm install --frozen-lockfile
        }
        Invoke-Native -FailureMessage "Frontend build failed" -Command {
            pnpm build
        }
    }
    finally {
        Pop-Location
    }
}

$jar = Get-ChildItem -Path (Join-Path $repoRoot "backend/target/*.jar") |
    Where-Object { $_.Name -notlike 'original-*' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "No backend jar found under backend/target."
}

Invoke-Step -Message "Start backend (java -jar)" -Action {
    $backendProcess = Start-Process -FilePath "java" `
        -ArgumentList @("-jar", $jar.FullName, "--server.port=$backendPort") `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $backendStdout `
        -RedirectStandardError $backendStderr `
        -PassThru
    Set-Content -Path $backendPidFile -Value $backendProcess.Id -Encoding UTF8
}

Invoke-Step -Message "Start frontend (vite preview)" -Action {
    $pnpmCmd = Get-Command pnpm.cmd -ErrorAction SilentlyContinue
    if (-not $pnpmCmd) {
        $pnpmCmd = Get-Command pnpm -ErrorAction Stop
    }
    $pnpmPath = $pnpmCmd.Source
    $frontendProcess = Start-Process -FilePath $pnpmPath `
        -ArgumentList @("preview", "--host", "0.0.0.0", "--port", "$frontendPort", "--strictPort") `
        -WorkingDirectory (Join-Path $repoRoot "frontend") `
        -RedirectStandardOutput $frontendStdout `
        -RedirectStandardError $frontendStderr `
        -PassThru
    Set-Content -Path $frontendPidFile -Value $frontendProcess.Id -Encoding UTF8
}

Invoke-Step -Message "Wait for services" -Action {
    Wait-Http -Url "http://127.0.0.1:$backendPort/actuator/health"
    Wait-Http -Url "http://127.0.0.1:$frontendPort"
}

Write-Host "Local deploy complete." -ForegroundColor Green
Write-Host "Frontend: http://127.0.0.1:$frontendPort"
Write-Host "Backend:  http://127.0.0.1:$backendPort"
Write-Host "Logs: $logDir"
