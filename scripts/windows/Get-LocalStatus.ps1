[CmdletBinding()]
param([switch]$AsJson)
Set-StrictMode -Version Latest
$rows = foreach($spec in @(@{Name='Backend';Port=11031;Url='http://127.0.0.1:11031/actuator/health'},@{Name='Frontend';Port=11030;Url='http://127.0.0.1:11030/'})) {
    $tcp = $false
    try { $c=[Net.Sockets.TcpClient]::new(); $t=$c.ConnectAsync('127.0.0.1',$spec.Port); $tcp=$t.Wait(1000)-and$c.Connected; $c.Dispose() } catch { $tcp=$false }
    $healthy=$false
    try { $r=Invoke-WebRequest -Uri $spec.Url -UseBasicParsing -TimeoutSec 3; $healthy=$r.StatusCode -eq 200 } catch { $healthy=$false }
    [pscustomobject]@{ product='AISocialGame'; component=$spec.Name; port=$spec.Port; tcpListening=$tcp; healthy=$healthy }
}
if($AsJson){$rows|ConvertTo-Json -Depth 3}else{$rows|Format-Table -AutoSize}
