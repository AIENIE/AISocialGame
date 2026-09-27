[CmdletBinding()] param(
    [string]$EvidenceDirectory,
    [ValidateRange(0,65535)][int]$Port=0,
    [ValidateSet('8.4.11','8.0.45')][string]$ServerVersion='8.4.11',
    [switch]$VerifyFailureCleanup
)
Set-StrictMode -Version Latest; $ErrorActionPreference='Stop'
if([Environment]::OSVersion.Platform -ne 'Win32NT' -or $PSVersionTable.PSVersion.Major -lt 7){throw 'Requires PowerShell 7 on Windows.'}
. (Join-Path $PSScriptRoot 'LocalCommand.ps1')
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$toolsRoot=Join-Path $env:LOCALAPPDATA 'Aienie/tools/aisocialgame'
$version=$ServerVersion; $package="mysql-$version-winx64"
$zip=Join-Path $toolsRoot "$package.zip"; $mysqlHome=Join-Path $toolsRoot $package
New-Item -ItemType Directory -Path $toolsRoot -Force | Out-Null
$url=if($version.StartsWith('8.4.')){"https://cdn.mysql.com/Downloads/MySQL-8.4/$package.zip"}else{"https://cdn.mysql.com/archives/mysql-8.0/$package.zip"}
$md5=(Invoke-WebRequest "$url.md5" -TimeoutSec 60).Content
$expected=([regex]::Match($md5,'[a-fA-F0-9]{32}')).Value
if(-not $expected){throw 'Official MySQL checksum unavailable.'}
if(-not(Test-Path $zip)){Invoke-WebRequest $url -OutFile $zip -TimeoutSec 600}
if((Get-FileHash $zip -Algorithm MD5).Hash -ine $expected){throw 'MySQL official package checksum mismatch.'}
if(-not(Test-Path $mysqlHome)){Expand-Archive -LiteralPath $zip -DestinationPath $toolsRoot}
$mysqld=Join-Path $mysqlHome 'bin/mysqld.exe'; $admin=Join-Path $mysqlHome 'bin/mysqladmin.exe'
if((& $mysqld --version) -notmatch "Ver $([regex]::Escape($version)) for Win64"){throw 'Wrong MySQL version.'}
$runId=[guid]::NewGuid().ToString('N')
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
try{$listener.Start();$Port=([Net.IPEndPoint]$listener.LocalEndpoint).Port}finally{$listener.Stop()}
if(-not $EvidenceDirectory){$EvidenceDirectory=Join-Path $env:LOCALAPPDATA "Aienie/artifacts/aisocialgame/mysql-$runId"}
$evidence=[IO.Path]::GetFullPath($EvidenceDirectory)
if($evidence.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path $evidence)){throw 'Use a new evidence directory outside the checkout.'}
New-Item -ItemType Directory -Path $evidence | Out-Null
$runRoot=Join-Path $toolsRoot "runs/$runId"
New-Item -ItemType Directory -Path $runRoot | Out-Null
& icacls.exe $runRoot /inheritance:r /grant:r "$([Security.Principal.WindowsIdentity]::GetCurrent().Name):(OI)(CI)F" | Out-Null
if($LASTEXITCODE){throw 'Cannot protect temporary credentials.'}
$data=Join-Path $runRoot 'data'; $config=Join-Path $runRoot 'my.ini'; $init=Join-Path $runRoot 'init.sql'; $client=Join-Path $runRoot 'client.ini'
$password=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
$pathSql=$data.Replace('\','/')
@"
[mysqld]
basedir="$($mysqlHome.Replace('\','/'))"
datadir="$pathSql"
bind-address=127.0.0.1
port=$Port
mysqlx=OFF
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
skip-log-bin
"@ | Set-Content $config
"ALTER USER 'root'@'localhost' IDENTIFIED BY '$password';" | Set-Content $init
"[client]`nuser=root`npassword=$password`nhost=127.0.0.1`nport=$Port`nprotocol=tcp" | Set-Content $client
$process=$null; $success=$false; $prior=@{}
foreach($key in @('AIENIE_CLOSURE_MYSQL','AIENIE_CLOSURE_MYSQL_PORT','AIENIE_CLOSURE_MYSQL_RUN','AIENIE_CLOSURE_MYSQL_PASSWORD','AIENIE_CLOSURE_MYSQL_EVIDENCE','AIENIE_CLOSURE_MYSQL_VERSION')){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try {
    & $mysqld "--defaults-file=$config" --initialize-insecure --console *> (Join-Path $evidence 'initialize.log')
    if($LASTEXITCODE){throw 'MySQL initialization failed.'}
    $arguments=@("--defaults-file=$config","--init-file=$init",'--console') | ForEach-Object {ConvertTo-LocalProcessArgument $_}
    $process=Start-Process -FilePath $mysqld -ArgumentList ($arguments -join ' ') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence 'mysql.stdout.log') -RedirectStandardError (Join-Path $evidence 'mysql.stderr.log')
    $ready=$false
    for($i=0;$i -lt 60;$i++){
        if($process.HasExited){throw 'Temporary MySQL exited before readiness.'}
        & $admin "--defaults-extra-file=$client" ping --silent *> $null
        if($LASTEXITCODE -eq 0){$ready=$true;break}; Start-Sleep -Milliseconds 500
    }
    if(-not $ready){throw 'Temporary MySQL readiness timed out.'}
    if($VerifyFailureCleanup){throw 'Intentional isolated runner cleanup check.'}
    $env:AIENIE_CLOSURE_MYSQL='1';$env:AIENIE_CLOSURE_MYSQL_PORT=[string]$Port;$env:AIENIE_CLOSURE_MYSQL_RUN=$runId;$env:AIENIE_CLOSURE_MYSQL_PASSWORD=$password;$env:AIENIE_CLOSURE_MYSQL_EVIDENCE=$evidence;$env:AIENIE_CLOSURE_MYSQL_VERSION=$version
    & mvn.cmd -q -f (Join-Path $root 'backend/pom.xml') '-Dtest=ClosureMySqlMigrationTest,GeneratedPlanMySqlTest' test *> (Join-Path $evidence 'migrations.log')
    if($LASTEXITCODE){throw 'MySQL migration tests failed; see migrations.log.'}
    & mvn.cmd -q -f (Join-Path $root 'backend/pom.xml') '-Dtest=ClosureMySqlAdmissionTest,ClosureMySqlGameIntegrationTest,ClosureMySqlCommunityTest,ClosureMySqlSettlementTest' test *> (Join-Path $evidence 'behavior.log')
    if($LASTEXITCODE){throw 'MySQL behavior tests failed; see behavior.log.'}
    & mvn.cmd -q -f (Join-Path $root 'backend/pom.xml') '-Dtest=ClosureMySqlArchiveScaleTest' test *> (Join-Path $evidence 'archive-scale.log')
    if($LASTEXITCODE){throw 'MySQL archive scale test failed; see archive-scale.log.'}
    $success=$true
} finally {
    foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')}
    if($process -and -not $process.HasExited){& $admin "--defaults-extra-file=$client" shutdown *> $null; if(-not $process.WaitForExit(15000)){$process.Kill();$process.WaitForExit()}}
    $listening=@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count -gt 0
    @{passed=$success;version=$version;port=$Port;listenerRemaining=$listening;packageUrl=$url;officialMd5=$expected;sha256=(Get-FileHash $zip -Algorithm SHA256).Hash} | ConvertTo-Json | Set-Content (Join-Path $evidence 'result.json')
    # Only this invocation's generated directory is eligible for removal.
    $resolved=[IO.Path]::GetFullPath($runRoot);$parent=[IO.Path]::GetFullPath((Join-Path $toolsRoot 'runs'))+[IO.Path]::DirectorySeparatorChar
    if(-not $resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -cne $runId){throw 'Unsafe temporary cleanup target.'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
    if($listening){throw 'Test port still has a listener.'}
}
Write-Host "PASS isolated MySQL; evidence: $evidence"
