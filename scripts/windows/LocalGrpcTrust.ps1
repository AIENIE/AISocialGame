function Get-LocalGrpcTrustUri {
    $path = Join-Path $PSScriptRoot 'local-trust\localcert-root-ca.crt'
    $item = Get-Item -LiteralPath $path -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Local gRPC trust certificate must be a regular file.'
    }
    $cert = [Security.Cryptography.X509Certificates.X509Certificate2]::new($item.FullName)
    if ($cert.Thumbprint -cne 'ABB779409203615F6864BC73A32A983B91E9D081') {
        throw 'Local gRPC trust certificate fingerprint mismatch.'
    }
    return ([Uri]::new($item.FullName)).AbsoluteUri
}
