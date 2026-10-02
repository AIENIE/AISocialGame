Set-StrictMode -Version Latest

function Read-ConfigPairValues {
    param([Parameter(Mandatory)][string]$ProjectRoot, [string]$Profile = 'local', [string]$EnvironmentFile)
    $reader = Join-Path $ProjectRoot 'scripts\config-pair\read_configuration.py'
    $python = (Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $arguments = @($reader, '--root', $ProjectRoot, '--profile', $Profile)
    if ($EnvironmentFile) {
        $application = $EnvironmentFile + '.application.yml'
        if (Test-Path -LiteralPath $application -PathType Leaf) {
            $item = Get-Item -LiteralPath $application -Force
            if ($item.Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) { throw 'YAML configuration must not be a link.' }
            $arguments += @('--application', $item.FullName)
        }
    }
    $json = & $python @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read non-sensitive YAML configuration.' }
    return ($json | ConvertFrom-Json -AsHashtable)
}

function Import-ConfigPairValues {
    param([Parameter(Mandatory)][string]$ProjectRoot, [string]$Profile = 'local', [string]$EnvironmentFile)
    $values = Read-ConfigPairValues -ProjectRoot $ProjectRoot -Profile $Profile -EnvironmentFile $EnvironmentFile
    foreach ($key in $values.Keys) {
        [Environment]::SetEnvironmentVariable($key, [string]$values[$key], 'Process')
    }
    if ($EnvironmentFile -and (Test-Path -LiteralPath ($EnvironmentFile + '.application.yml') -PathType Leaf)) {
        $env:AIENIE_APPLICATION_FILE = ([Uri][IO.Path]::GetFullPath($EnvironmentFile + '.application.yml')).AbsoluteUri
    }
}
