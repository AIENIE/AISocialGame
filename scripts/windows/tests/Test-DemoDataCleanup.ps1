[CmdletBinding()]param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$classPathFile=Join-Path $repoRoot 'backend/target/demo-cleanup-classpath.txt'
& mvn.cmd -q -f (Join-Path $repoRoot 'backend/pom.xml') dependency:build-classpath '-DincludeScope=test' "-Dmdep.outputFile=$classPathFile"
if($LASTEXITCODE){throw 'Cleanup test dependencies unavailable.'}
$classPath=(Get-Content -LiteralPath $classPathFile -Raw).Trim()
$classes=Join-Path $repoRoot 'backend/target/demo-cleanup-tests'
New-Item -ItemType Directory -Force $classes | Out-Null
$support=Join-Path $repoRoot 'scripts/windows/support'
& javac.exe -encoding UTF-8 -cp $classPath -d $classes (Join-Path $support 'DemoDataCleanup.java') (Join-Path $support 'DemoDataCleanupTest.java')
if($LASTEXITCODE){throw 'Cleanup test compilation failed.'}
& java.exe -cp "$classes;$classPath" DemoDataCleanupTest (Join-Path $support 'demo-seed-manifest.json')
if($LASTEXITCODE){throw 'Cleanup tests failed.'}
