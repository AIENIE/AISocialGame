function Assert-AdminEmergencyAcceptance {
    param([hashtable]$Values, [hashtable]$YamlValues, $SharedTarget, [int]$BackendPort, [switch]$IsolatedAcceptance)
    $selected = [string]$Values['AIENIE_ADMIN_EMERGENCY_ACCEPTANCE']
    if (-not $IsolatedAcceptance) {
        if ($BackendPort -ne 11031 -or ($selected -ne '' -and $selected -cne 'false')) { throw 'Custom admin acceptance requires the explicit isolated switch and YAML selector.' }
        return
    }
    if ($PSVersionTable.Platform -cne 'Win32NT' -or $BackendPort -ne 12031 -or
        [string]$YamlValues['AIENIE_ADMIN_EMERGENCY_ACCEPTANCE'] -cne 'true' -or $selected -cne 'true' -or
        [string]$Values['ENV'] -cne 'local' -or [string]$Values['AUTH_MODE'] -cne 'totp' -or
        [string]$Values['AIENIE_RUNTIME_PLANE'] -cne 'windows-local' -or
        [string]$Values['SERVER_ADDRESS'] -cne '127.0.0.1' -or [string]$Values['SERVER_PORT'] -cne '12031') {
        throw 'Admin emergency acceptance requires its explicit local Windows/TOTP/loopback YAML configuration and port 12031.'
    }
    if ($Values.ContainsKey('SPRING_PROFILES_ACTIVE') -and [string]$Values['SPRING_PROFILES_ACTIVE'] -cne 'local') { throw 'Admin emergency acceptance rejects another active profile.' }
    if ($SharedTarget.status -cne 'PASS' -or $SharedTarget.database -cne 'aienie_emergency_20261004_social' -or
        $SharedTarget.configuredTarget.host -cne 'localmysql.testhut.top' -or $SharedTarget.configuredTarget.port -ne 23306 -or
        $SharedTarget.matrixTarget.host -cne $SharedTarget.configuredTarget.host -or $SharedTarget.matrixTarget.port -ne $SharedTarget.configuredTarget.port) {
        throw 'Admin emergency acceptance requires its own schema and the confirmed matrix shared MySQL target.'
    }
}
