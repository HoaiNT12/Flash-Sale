#requires -Version 5.1
<#
Run the insufficient-funds or sold-out scenario without variables from another session.
Uses the existing default stack on ports 8081..8084. Does not reset Docker or databases.
#>
[CmdletBinding()]
param(
    [ValidateSet('poor', 'soldout', 'all')][string]$Scenario = 'all',
    [ValidateNotNullOrEmpty()][string]$Password = 'Demo@123',
    [ValidateNotNullOrEmpty()][string]$AuthUrl = 'http://127.0.0.1:8081',
    [ValidateNotNullOrEmpty()][string]$InventoryUrl = 'http://127.0.0.1:8082',
    [ValidateNotNullOrEmpty()][string]$PaymentUrl = 'http://127.0.0.1:8083',
    [ValidateNotNullOrEmpty()][string]$OrderUrl = 'http://127.0.0.1:8084',
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'powershell-compat.ps1')
$urls = [ordered]@{ auth = $AuthUrl; inventory = $InventoryUrl; payment = $PaymentUrl; order = $OrderUrl }
# PSScriptRoot is available in the script body in Windows PowerShell 5.1.
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot ("../test-results/order-scenarios-{0}-{1}" -f (Get-Date -Format 'yyyyMMddHHmmss'), [guid]::NewGuid().ToString('N').Substring(0, 8))
}
$outputRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
if ((Test-Path -LiteralPath $outputRoot) -and @(Get-ChildItem -LiteralPath $outputRoot -Force).Count -gt 0) {
    throw "OutputDirectory must be empty; preserve previous evidence: $outputRoot"
}
foreach ($service in @('auth', 'inventory', 'payment', 'order')) {
    $url = [uri]$urls[$service]
    if (-not $url.IsAbsoluteUri -or $url.Scheme -notin @('http', 'https') -or $url.UserInfo -or $url.Query -or $url.Fragment) {
        throw "$service URL must be an absolute HTTP(S) URL without credentials, query or fragment"
    }
    $urls[$service] = $url.AbsoluteUri.TrimEnd('/')
    try {
        $health = Invoke-RestMethod -Method Get -Uri "$($urls[$service])/actuator/health" -TimeoutSec 10
        if ($health.status -ne 'UP') { throw 'Health status is not UP' }
    } catch { throw "$service is not ready at $($urls[$service]). No orders submitted. $($_.Exception.Message)" }
}
$selected = if ($Scenario -eq 'all') { @('poor', 'soldout') } else { @($Scenario) }
$configs = @{}
# Preflight every selected scenario before submitting any checkout request.
foreach ($name in $selected) {
    $config = ConvertFrom-CompatJson -InputObject (Get-Content -LiteralPath (Join-Path $PSScriptRoot '../examples/batch-orders.input.json') -Raw -Encoding UTF8) -AsHashtable
    $config['baseUrls'] = $urls
    $config['customer'] = @{ username = $(if ($name -eq 'poor') { 'poor' } else { 'alice' }); password = $Password }
    $config['productId'] = if ($name -eq 'poor') { '22222222-2222-2222-2222-222222222222' } else { '33333333-3333-3333-3333-333333333333' }
    $config['quantity'] = 1
    $config['uniqueOrders'] = 10
    $config['duplicateRequests'] = 10
    $config['concurrency'] = 16
    $headers = $null; $login = $null
    try {
        $body = $config['customer'] | ConvertTo-Json -Compress
        $login = Invoke-RestMethod -Method Post -Uri "$($urls.auth)/api/auth/login" -ContentType 'application/json' -Body $body -TimeoutSec 10
        $headers = @{ Authorization = "Bearer $($login.accessToken)" }
        $product = Invoke-RestMethod -Method Get -Uri "$($urls.inventory)/api/products/$($config['productId'])" -Headers $headers -TimeoutSec 10
        if ($name -eq 'poor') {
            $wallet = Invoke-RestMethod -Method Get -Uri "$($urls.payment)/api/wallets/me" -Headers $headers -TimeoutSec 10
            if ([decimal]$wallet.balance -ne 0) { throw 'Expected poor wallet balance = 0; no orders submitted.' }
            if ([int]$product.availableStock -lt 10) { throw 'SKU 2 needs at least 10 available units for this concurrent insufficient-funds test; no orders submitted.' }
        } elseif ([int]$product.availableStock -ne 0) {
            throw 'Expected SKU 3 stock = 0 for soldout; no orders submitted.'
        }
    } finally { $login = $null; $headers = $null; $body = $null }
    $configs[$name] = $config
}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
Write-Host "Output: $outputRoot"
foreach ($name in $selected) {
    $runDirectory = Join-Path $outputRoot $name
    $inputPath = Join-Path $outputRoot "$name.input.json"
    # Persist a credential-free input. Supply credentials only to the temporary copy.
    $privateInput = Join-Path $outputRoot ('.' + $name + '.credentials-' + [guid]::NewGuid().ToString('N') + '.json')
    try {
        [IO.File]::WriteAllText($privateInput, (ConvertTo-Json -InputObject $configs[$name] -Depth 8), [Text.UTF8Encoding]::new($false))
        $null = & (Join-Path $PSScriptRoot 'batch-orders.ps1') -InputFile $privateInput -OutputDirectory $runDirectory
        $result = ConvertFrom-CompatJson -InputObject (Get-Content -LiteralPath (Join-Path $runDirectory 'summary.json') -Raw -Encoding UTF8) -AsHashtable
        $expected = if ($name -eq 'poor') { 'INSUFFICIENT_FUNDS' } else { 'OUT_OF_STOCK' }
        $reasons = $result['rejectionReasons']
        if ($result['status'] -cne 'PASS' -or [int]$result['distinctOrders'] -ne 10 -or [int]$result['rejectedOrders'] -ne 10 -or $null -eq $reasons -or -not $reasons.Contains($expected) -or [int]$reasons[$expected] -ne 10) {
            throw "Expected 10 rejected orders with $expected; inspect $runDirectory/summary.json"
        }
        [pscustomobject]@{ scenario = $name; status = 'PASS'; distinctOrders = $result['distinctOrders']; rejectedOrders = $result['rejectedOrders']; rejectionReasons = $reasons; observedConsistency = $result['observedConsistency']; outputDirectory = $runDirectory } | ConvertTo-Json -Depth 8
    } catch { throw "Scenario $name failed. Evidence: $runDirectory. $($_.Exception.Message)" }
    finally {
        $configs[$name]['customer'].Remove('password')
        [IO.File]::WriteAllText($inputPath, (ConvertTo-Json -InputObject $configs[$name] -Depth 8), [Text.UTF8Encoding]::new($false))
        # Delete only the exact temporary credential file generated inside this output folder.
        if (Test-Path -LiteralPath $privateInput) { Remove-Item -LiteralPath $privateInput -Force }
    }
}