function Assert-ClosureGrant {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Grant)
    if($Grant['documentKind'] -eq 'UNAUTHORIZED_EXECUTION_PROPOSAL' -or $Grant['authorized'] -ceq $false){throw 'An execution proposal is not an approval.'}
    foreach($key in @('approvedBy','approvalReference','expiresAt','callerId','sourceFingerprint','buildId','batchId')) {
        if($Grant[$key] -isnot [string] -or [string]::IsNullOrWhiteSpace($Grant[$key])){throw 'An explicit, version-bound execution grant is required.'}
    }
    $expires=[DateTimeOffset]::MinValue
    if(-not [DateTimeOffset]::TryParse($Grant['expiresAt'],[ref]$expires) -or $expires -le [DateTimeOffset]::UtcNow -or $Grant['model'] -cne 'deepseek-flash'){throw 'Grant is expired or model differs.'}
}
