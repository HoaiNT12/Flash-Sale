#requires -Version 5.1
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')]
    [string]$ComposeProject,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputDirectory,
    [ValidateLength(0, 128)]
    [string]$RunPrefix = '',
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$Phase = 'after'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'powershell-compat.ps1')
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$files = [ordered]@{}

function Export-Snapshot {
    param(
        [string]$Service,
        [string]$Database,
        [string]$Kind,
        [hashtable]$Variables = @{}
    )
    $sqlPath = Join-Path $PSScriptRoot "sql/batch-$Kind.sql"
    $nativeArguments = @(
        'compose', '--env-file', '.env.example', '-p', $ComposeProject,
        '-f', 'docker-compose.yml', '-f', 'docker-compose.batch-test.yml',
        'exec', '-T', $Service, 'psql', '-U', 'postgres', '-d', $Database,
        '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1'
    )
    $prelude = [Collections.Generic.List[string]]::new()
    foreach ($name in @($Variables.Keys | Sort-Object)) {
        if ($name -notmatch '^[a-z][a-z0-9_]*$') { throw 'Invalid audit SQL variable name' }
        # Native Windows argument handling differs between PS5.1 and PS7. Pass
        # values through stdin as ASCII hex instead: no JSON quote stripping,
        # command-line length limit, or untrusted text in SQL/psql directives.
        $bytes = [Text.Encoding]::UTF8.GetBytes([string]$Variables[$name])
        $hex = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
        $prelude.Add(("\set {0}_hex '{1}'" -f $name, $hex))
        $prelude.Add(("SELECT convert_from(decode(:'{0}_hex', 'hex'), 'UTF8') AS {0} \gset" -f $name))
    }
    $sql = ($prelude.ToArray() -join "`n") + "`n" + (Get-Content -LiteralPath $sqlPath -Raw -Encoding utf8)
    # Input encoding is local to this function. Native output decoding follows
    # Console.OutputEncoding on Windows PowerShell, so restore it after the call.
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    $previousConsoleEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
        $raw = @($sql | & docker @nativeArguments)
        $nativeExitCode = $LASTEXITCODE
    } finally {
        [Console]::OutputEncoding = $previousConsoleEncoding
    }
    if ($nativeExitCode -ne 0) {
        throw "Docker/psql audit for $Kind failed with exit code $nativeExitCode"
    }
    $json = ($raw -join "`n").Trim()
    if ([string]::IsNullOrWhiteSpace($json)) { throw "Empty $Kind audit response" }
    $snapshot = ConvertFrom-CompatJson -InputObject $json
    if ($null -eq $snapshot -or $snapshot.database -ne $Database) {
        throw "Unexpected database in $Kind audit response"
    }
    $destination = Join-Path $outputRoot "$Phase.$Kind.json"
    [IO.File]::WriteAllText($destination, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    $files[$Kind] = $destination
    return $snapshot
}

Push-Location -LiteralPath $projectRoot
try {
    foreach ($required in @('.env.example', 'docker-compose.yml', 'docker-compose.batch-test.yml')) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing project file $required" }
    }

    # Database discovery includes orders committed after an HTTP response was lost.
    # Empty prefix selects every order in this explicitly selected Compose project.
    $orders = Export-Snapshot -Service 'postgres-primary' -Database 'ordersdb' -Kind 'orders' -Variables @{run_prefix = $RunPrefix}
    $orderIds = @($orders.order_ids)
    $orderIdsJson = ConvertTo-Json -InputObject $orderIds -Compress -Depth 5
    $scope = @{order_ids = $orderIdsJson}
    $inventory = Export-Snapshot -Service 'postgres-primary' -Database 'inventorydb' -Kind 'inventory' -Variables $scope
    $payment = Export-Snapshot -Service 'postgres-primary' -Database 'paymentdb' -Kind 'payment' -Variables $scope
    $replica = Export-Snapshot -Service 'postgres-replica' -Database 'inventorydb' -Kind 'replica'

    [pscustomobject]@{
        files = $files
        counts = [ordered]@{
            orders = @($orders.orders).Count
            unfinished = [int]$orders.unfinished
            reservations = @($inventory.reservations).Count
            payments = @($payment.payments).Count
            ledgerEvents = @($payment.ledger).Count
            replicaProducts = @($replica.products).Count
        }
    } | ConvertTo-Json -Depth 6 -Compress
} catch {
    Write-Error "Batch audit failed: $($_.Exception.Message)" -ErrorAction Continue
    exit 1
} finally {
    Pop-Location
}
