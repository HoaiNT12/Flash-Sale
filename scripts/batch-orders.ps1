#requires -Version 7.0
<#
Run against an isolated, idle stack with demo faults reset. The default input sends
200 unique checkout keys and 100 duplicate requests in a mixed concurrent batch,
then deliberately reuses one accepted key with a different quantity (HTTP 409).
Use a different input file for customer=poor or a sold-out product.

No password or bearer token is written to artifacts. rawrequests.jsonl contains
the checkout POST payloads and safe headers actually sent; responses.jsonl keeps
actual HTTP status/body/latency. Recovery retries reuse the exact original key
and body. Authentication and GET polling are excluded from checkout POST counts.
#>
[CmdletBinding()]
param(
    [string]$InputFile = (Join-Path $PSScriptRoot '../examples/batch-orders.input.json'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot ("../artifacts/batch-orders/{0}-{1}" -f [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'), [guid]::NewGuid().ToString('N').Substring(0, 8)))
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-JsonFile([string]$Path, $Value) {
    ConvertTo-Json -InputObject $Value -Depth 64 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Add-JsonLine([string]$Path, $Value) {
    ConvertTo-Json -InputObject $Value -Depth 64 -Compress | Add-Content -LiteralPath $Path -Encoding utf8
}

function Config-Integer([System.Collections.IDictionary]$Config, [string]$Name, [int]$Default, [int]$Minimum, [int]$Maximum) {
    $value = if ($Config.Contains($Name)) { $Config[$Name] } else { $Default }
    $integer = 0
    if (-not [int]::TryParse([string]$value, [ref]$integer) -or $integer -lt $Minimum -or $integer -gt $Maximum) {
        throw "$Name must be an integer in $Minimum..$Maximum"
    }
    return $integer
}

function New-Request([int]$Index, [int]$LogicalIndex, [string]$Phase, [string]$Key, [string]$Url, [string]$BodyText, [int]$Attempt = 0) {
    return [ordered]@{
        requestIndex = $Index; logicalOrderIndex = $LogicalIndex; phase = $Phase
        method = 'POST'; url = $Url; idempotencyKey = $Key; bodyText = $BodyText; recoveryAttempt = $Attempt
    }
}

# Self-contained so the same request implementation can run in parallel runspaces.
$invokeHttp = {
    param($Request, [string]$AccessToken, [int]$HttpTimeoutSeconds)
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    $started = [DateTime]::UtcNow
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $status = 0; $bodyText = $null; $body = $null; $transportError = $null
    $safeResponseHeaders = @{}
    try {
        $headers = @{ Authorization = "Bearer $AccessToken" }
        if ($Request.idempotencyKey) { $headers['Idempotency-Key'] = $Request.idempotencyKey }
        $parameters = @{
            Method = $Request.method; Uri = $Request.url; Headers = $headers
            TimeoutSec = $HttpTimeoutSeconds; SkipHttpErrorCheck = $true; ErrorAction = 'Stop'
        }
        if ($Request.method -eq 'POST') { $parameters.ContentType = 'application/json'; $parameters.Body = $Request.bodyText }
        $http = Invoke-WebRequest @parameters
        $status = [int]$http.StatusCode
        $bodyText = [string]$http.Content
        if ($bodyText) {
            try { $body = ConvertFrom-Json -InputObject $bodyText -AsHashtable -Depth 64 } catch { $body = $bodyText }
        }
        foreach ($name in @('Location', 'Retry-After', 'Content-Type')) {
            if ($http.Headers.ContainsKey($name)) { $safeResponseHeaders[$name] = [string]::Join(', ', $http.Headers[$name]) }
        }
    } catch {
        # Exception type is enough to diagnose transport failures without logging headers.
        $transportError = $_.Exception.GetType().FullName
    } finally { $timer.Stop() }
    $safeHeaders = @{}
    if ($Request.idempotencyKey) { $safeHeaders['Idempotency-Key'] = $Request.idempotencyKey }
    if ($Request.method -eq 'POST') { $safeHeaders['Content-Type'] = 'application/json' }
    $requestBody = $null
    if ($Request.bodyText) { $requestBody = ConvertFrom-Json -InputObject $Request.bodyText -AsHashtable }
    [pscustomobject]@{
        request = [ordered]@{
            requestIndex = $Request.requestIndex; logicalOrderIndex = $Request.logicalOrderIndex; phase = $Request.phase
            method = $Request.method; url = $Request.url; headers = $safeHeaders; idempotencyKey = $Request.idempotencyKey
            body = $requestBody; bodyText = $Request.bodyText; recoveryAttempt = $Request.recoveryAttempt
            sentAtUtc = $started.ToString('o'); httpTimeoutSeconds = $HttpTimeoutSeconds
        }
        response = [ordered]@{
            requestIndex = $Request.requestIndex; logicalOrderIndex = $Request.logicalOrderIndex; phase = $Request.phase
            idempotencyKey = $Request.idempotencyKey; recoveryAttempt = $Request.recoveryAttempt
            startedAtUtc = $started.ToString('o'); finishedAtUtc = [DateTime]::UtcNow.ToString('o')
            httpStatus = $status; headers = $safeResponseHeaders; body = $body; bodyText = $bodyText
            latencyMs = [Math]::Round($timer.Elapsed.TotalMilliseconds, 3); transportError = $transportError
            outcomeUnknown = ($status -eq 0 -or $status -ge 500)
        }
    }
}
$invokeHttpText = $invokeHttp.ToString()

function Latency-Percentiles($Responses) {
    $values = @($Responses | ForEach-Object { [double]$_.latencyMs } | Sort-Object)
    if ($values.Count -eq 0) { return @{ p50 = $null; p95 = $null; p99 = $null } }
    $result = [ordered]@{}
    foreach ($percent in @(50, 95, 99)) {
        $index = [Math]::Max(0, [int][Math]::Ceiling($values.Count * $percent / 100.0) - 1)
        $result["p$percent"] = $values[$index]
    }
    return $result
}

function Status-Counts($Responses) {
    $counts = [ordered]@{}
    foreach ($group in @($Responses | Group-Object { $_.httpStatus } | Sort-Object { [int]$_.Name })) {
        $counts[$group.Name] = $group.Count
    }
    return $counts
}

$config = Get-Content -LiteralPath $InputFile -Raw | ConvertFrom-Json -AsHashtable -Depth 32
$uniqueOrders = Config-Integer $config 'uniqueOrders' 200 1 10000
$duplicateRequests = Config-Integer $config 'duplicateRequests' 100 0 10000
$concurrency = Config-Integer $config 'concurrency' 32 1 128
$timeoutSeconds = Config-Integer $config 'timeoutSeconds' 240 10 600
$quantity = Config-Integer $config 'quantity' 1 1 5
$productId = [guid]::Empty
if (-not [guid]::TryParse([string]$config['productId'], [ref]$productId)) { throw 'productId must be a UUID' }
$baseUrls = [ordered]@{}
foreach ($service in @('auth', 'inventory', 'payment', 'order')) {
    $url = [uri]$config['baseUrls'][$service]
    if (-not $url.IsAbsoluteUri -or $url.Scheme -notin @('http', 'https') -or $url.UserInfo -or $url.Query -or $url.Fragment) {
        throw "baseUrls.$service must be an absolute HTTP(S) URL without credentials, query or fragment"
    }
    $baseUrls[$service] = $url.AbsoluteUri.TrimEnd('/')
}
$customer = [string]$config['customer']['username']
if ([string]::IsNullOrWhiteSpace($customer)) { throw 'customer.username is required' }
$password = if ($config['customer'].Contains('password')) { [string]$config['customer']['password'] } else { 'Demo@123' }
$runId = [guid]::NewGuid().ToString('N')
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if ((Test-Path -LiteralPath $OutputDirectory) -and @(Get-ChildItem -LiteralPath $OutputDirectory -Force).Count -gt 0) {
    throw 'OutputDirectory must be empty; use a new directory to preserve earlier evidence'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$paths = @{}
foreach ($name in @('rawrequests', 'responses', 'polls', 'reads')) {
    $paths[$name] = Join-Path $OutputDirectory "$name.jsonl"
    [IO.File]::WriteAllText($paths[$name], '')
}
$responses = [Collections.Generic.List[object]]::new()
$failures = [Collections.Generic.List[string]]::new()
$knownByKey = @{}; $latestOrders = @{}; $originalByKey = [ordered]@{}
$orderUrl = "$($baseUrls.order)/api/orders"
$bodyText = @{ productId = $productId.ToString(); quantity = $quantity } | ConvertTo-Json -Compress
$requests = [Collections.Generic.List[object]]::new()
for ($index = 1; $index -le $uniqueOrders; $index++) {
    $key = "batch-$runId-$index"
    $request = New-Request $index $index 'bulk' $key $orderUrl $bodyText
    $originalByKey[$key] = $request
    $requests.Add($request)
}
for ($index = 1; $index -le $duplicateRequests; $index++) {
    $logicalIndex = (($index - 1) % $uniqueOrders) + 1
    $requests.Add((New-Request ($uniqueOrders + $index) $logicalIndex 'bulk' "batch-$runId-$logicalIndex" $orderUrl $bodyText))
}
$manifest = [ordered]@{
    runId = $runId; createdAtUtc = [DateTime]::UtcNow.ToString('o'); baseUrls = $baseUrls
    customer = @{ username = $customer }; productId = $productId.ToString(); quantity = $quantity
    uniqueOrders = $uniqueOrders; duplicateRequests = $duplicateRequests; concurrency = $concurrency; timeoutSeconds = $timeoutSeconds
    plannedBulkHttpRequests = $requests.Count; plannedConflictProbes = 1
    keyMapping = @($originalByKey.Values | ForEach-Object { @{ logicalOrderIndex = $_.logicalOrderIndex; idempotencyKey = $_.idempotencyKey } })
    credentialHandling = 'Password and access token intentionally omitted'
    httpCountScope = 'Checkout POST requests only; authentication, baseline reads and GET polling are excluded'
}
Write-JsonFile (Join-Path $OutputDirectory 'input.manifest.json') $manifest
$summary = [ordered]@{
    runId = $runId; status = 'RUNNING'; outputDirectory = $OutputDirectory
    customer = $customer; productId = $productId.ToString(); quantity = $quantity
    plannedUniqueOrders = $uniqueOrders; plannedDuplicateRequests = $duplicateRequests
    plannedBulkHttpRequests = $requests.Count; plannedHttpRequests = $requests.Count + 1
    conflictProbe = $null; submissionDurationMs = $null; completionDurationMs = $null
    consistencyScope = 'HTTP-observed wallet/stock totals on an isolated stack; ledger/orphan checks require a separate primary DB audit'
}
Write-JsonFile (Join-Path $OutputDirectory 'summary.json') $summary
$runTimer = [Diagnostics.Stopwatch]::StartNew()
$accessToken = $null; $baselineProduct = $null; $baselineWallet = $null
$finalProduct = $null; $finalWallet = $null

function Save-Submission($Result) {
    Add-JsonLine $paths.rawrequests $Result.request
    Add-JsonLine $paths.responses $Result.response
    $responses.Add($Result.response)
    if ($Result.response.phase -eq 'conflict') { return }
    $body = $Result.response.body
    if ($Result.response.httpStatus -eq 202 -and $body -is [System.Collections.IDictionary] -and $body['id']) {
        $parsedId = [guid]::Empty
        if ([guid]::TryParse([string]$body['id'], [ref]$parsedId)) {
            $id = $parsedId.ToString()
            $knownByKey[$Result.response.idempotencyKey] = $id
            $latestOrders[$id] = $body
        }
    }
}

function Read-Snapshot([string]$Url, [string]$Phase) {
    $request = @{ requestIndex = 0; logicalOrderIndex = 0; phase = $Phase; method = 'GET'; url = $Url; idempotencyKey = $null; bodyText = $null; recoveryAttempt = 0 }
    $result = & $invokeHttp $request $accessToken 10
    Add-JsonLine $paths.reads @{ request = $result.request; response = $result.response }
    if ($result.response.httpStatus -ne 200 -or $result.response.body -isnot [System.Collections.IDictionary]) {
        throw "Snapshot '$Phase' failed with actual HTTP $($result.response.httpStatus); see reads.jsonl"
    }
    return $result.response.body
}

try {
    # Login response contains a bearer token and must never be recorded or printed.
    $login = Invoke-WebRequest -Method Post -Uri "$($baseUrls.auth)/api/auth/login" -ContentType 'application/json' -TimeoutSec 10 -SkipHttpErrorCheck -Body (@{ username = $customer; password = $password } | ConvertTo-Json -Compress)
    if ([int]$login.StatusCode -ne 200) { throw "Authentication failed with HTTP $([int]$login.StatusCode)" }
    $accessToken = [string](($login.Content | ConvertFrom-Json -AsHashtable)['accessToken'])
    if (-not $accessToken) { throw 'Authentication returned no access token' }
    $login = $null; $password = $null; $config = $null
    $baselineProduct = Read-Snapshot "$($baseUrls.inventory)/api/products/$productId" 'product-before'
    $baselineWallet = Read-Snapshot "$($baseUrls.payment)/api/wallets/me" 'wallet-before'

    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    $submissionTimer = [Diagnostics.Stopwatch]::StartNew()
    # Shuffle unique and duplicate payloads together; duplicates may reach the API first.
    $mixedRequests = @($requests | Sort-Object { Get-Random })
    $mixedRequests | ForEach-Object -ThrottleLimit $concurrency -Parallel {
        $remaining = [int][Math]::Floor(($using:deadline - [DateTime]::UtcNow).TotalSeconds)
        if ($remaining -ge 1) {
            $send = [scriptblock]::Create($using:invokeHttpText)
            & $send $_ $using:accessToken ([Math]::Min(10, $remaining))
        }
    } | ForEach-Object { Save-Submission $_ }
    $submissionTimer.Stop()
    $summary.submissionDurationMs = $submissionTimer.ElapsedMilliseconds
    $completionTimer = [Diagnostics.Stopwatch]::StartNew()
    $nextIndex = $requests.Count

    # A timeout does not prove rejection. Reconcile unresolved keys, never create new keys.
    # Also retry 429 with the same key because a concurrent duplicate may already exist.
    foreach ($key in @($originalByKey.Keys)) {
        if ($knownByKey.ContainsKey($key)) { continue }
        $earlier = @($responses | Where-Object idempotencyKey -eq $key)
        $recoverable = @($earlier | Where-Object { $_.httpStatus -eq 0 -or $_.httpStatus -eq 429 -or $_.httpStatus -ge 500 -or $_.httpStatus -eq 202 }).Count -gt 0
        if (-not $recoverable) { continue }
        for ($attempt = 1; $attempt -le 3 -and -not $knownByKey.ContainsKey($key); $attempt++) {
            $remaining = [int][Math]::Floor(($deadline - [DateTime]::UtcNow).TotalSeconds)
            if ($remaining -lt 1) { break }
            $nextIndex++
            $original = $originalByKey[$key]
            $recovery = New-Request $nextIndex $original.logicalOrderIndex 'recovery' $key $orderUrl $original.bodyText $attempt
            Save-Submission (& $invokeHttp $recovery $accessToken ([Math]::Min(10, $remaining)))
            if (-not $knownByKey.ContainsKey($key)) { Start-Sleep -Milliseconds (200 * $attempt) }
        }
    }

    # Probe only an acknowledged key so a failed create cannot accidentally become the probe.
    $probeKey = @($originalByKey.Keys | Where-Object { $knownByKey.ContainsKey($_) } | Select-Object -First 1)
    if ($probeKey.Count -gt 0 -and [DateTime]::UtcNow -lt $deadline) {
        $nextIndex++
        $changedQuantity = if ($quantity -eq 1) { 2 } else { 1 }
        $probeBody = @{ productId = $productId.ToString(); quantity = $changedQuantity } | ConvertTo-Json -Compress
        $original = $originalByKey[$probeKey[0]]
        $probe = New-Request $nextIndex $original.logicalOrderIndex 'conflict' $probeKey[0] $orderUrl $probeBody
        $probeResult = & $invokeHttp $probe $accessToken 10
        Save-Submission $probeResult
        $summary.conflictProbe = @{ httpStatus = $probeResult.response.httpStatus; idempotencyKey = $probeKey[0]; expectedHttpStatus = 409 }
    }

    $pollIndex = 0
    do {
        $pendingIds = @($latestOrders.Keys | Where-Object { $latestOrders[$_]['status'] -notin @('COMPLETED', 'REJECTED') })
        if ($pendingIds.Count -eq 0 -or [DateTime]::UtcNow -ge $deadline) { break }
        $pollRequests = foreach ($id in $pendingIds) {
            $pollIndex++
            @{ requestIndex = $pollIndex; logicalOrderIndex = 0; phase = 'poll'; method = 'GET'; url = "$orderUrl/$id"; idempotencyKey = $null; bodyText = $null; recoveryAttempt = 0 }
        }
        $pollRequests | ForEach-Object -ThrottleLimit $concurrency -Parallel {
            $remaining = [int][Math]::Floor(($using:deadline - [DateTime]::UtcNow).TotalSeconds)
            if ($remaining -ge 1) {
                $send = [scriptblock]::Create($using:invokeHttpText)
                & $send $_ $using:accessToken ([Math]::Min(10, $remaining))
            }
        } | ForEach-Object {
            Add-JsonLine $paths.polls @{ request = $_.request; response = $_.response }
            if ($_.response.httpStatus -eq 200 -and $_.response.body -is [System.Collections.IDictionary]) {
                $body = $_.response.body
                if ($body['id'] -and $latestOrders.ContainsKey([string]$body['id'])) { $latestOrders[[string]$body['id']] = $body }
            }
        }
        if ([DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 300 }
    } while ([DateTime]::UtcNow -lt $deadline)
    $completionTimer.Stop()
    # Completion duration is the wait AFTER bulk submissions finished, including recovery/probe.
    $summary.completionDurationMs = $completionTimer.ElapsedMilliseconds
    $summary.submissionAndCompletionDurationMs = $submissionTimer.ElapsedMilliseconds + $completionTimer.ElapsedMilliseconds

    $completed = @($latestOrders.Values | Where-Object { $_['status'] -eq 'COMPLETED' })
    $rejected = @($latestOrders.Values | Where-Object { $_['status'] -eq 'REJECTED' })
    $pending = @($latestOrders.Values | Where-Object { $_['status'] -notin @('COMPLETED', 'REJECTED') })
    $completedUnits = 0; $chargedVnd = [decimal]0
    foreach ($order in $completed) { $completedUnits += [int]$order['quantity']; $chargedVnd += [decimal]$order['amount'] }
    $finalWallet = Read-Snapshot "$($baseUrls.payment)/api/wallets/me" 'wallet-after'
    $expectedStock = [int]$baselineProduct['availableStock'] - $completedUnits
    $replicaDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $finalProduct = Read-Snapshot "$($baseUrls.inventory)/api/products/$productId" 'product-after'
        if ([int]$finalProduct['availableStock'] -eq $expectedStock) { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $replicaDeadline)
    $summary.observedConsistency = @{
        stockBefore = [int]$baselineProduct['availableStock']; stockAfter = [int]$finalProduct['availableStock']; expectedStockAfter = $expectedStock
        walletBefore = [decimal]$baselineWallet['balance']; walletAfter = [decimal]$finalWallet['balance']
        walletDebitedVnd = [decimal]$baselineWallet['balance'] - [decimal]$finalWallet['balance']
        completedOrderAmountVnd = $chargedVnd; completedUnits = $completedUnits
    }
    $duplicateMismatches = @(
        $responses | Where-Object { $_.phase -ne 'conflict' -and $_.httpStatus -eq 202 -and $_.body -is [System.Collections.IDictionary] -and $_.body['id'] } |
            Group-Object { $_.idempotencyKey } | ForEach-Object {
                $ids = @($_.Group | ForEach-Object { [string]$_.body['id'] } | Sort-Object -Unique)
                if ($ids.Count -gt 1) { @{ idempotencyKey = $_.Name; orderIds = $ids } }
            }
    )
    $summary.duplicateIdMismatches = $duplicateMismatches
    $summary.assertions = [ordered]@{
        allBulkRequestsRecorded = @($responses | Where-Object phase -eq 'bulk').Count -eq $requests.Count
        noPermanentBulkHttpErrors = @($responses | Where-Object { $_.phase -eq 'bulk' -and $_.httpStatus -notin @(0, 202, 429) -and $_.httpStatus -lt 500 }).Count -eq 0
        everyLogicalOrderAcknowledged = $knownByKey.Count -eq $uniqueOrders
        noDuplicateOrderIds = $duplicateMismatches.Count -eq 0
        conflictReturns409 = $null -ne $summary.conflictProbe -and $summary.conflictProbe.httpStatus -eq 409
        allKnownOrdersTerminal = $pending.Count -eq 0
        completedUnitsWithinBaselineStock = $completedUnits -le [int]$baselineProduct['availableStock']
        stockMatchesCompletedUnits = $pending.Count -eq 0 -and [int]$finalProduct['availableStock'] -eq $expectedStock
        walletMatchesCompletedOrderAmounts = $pending.Count -eq 0 -and ([decimal]$baselineWallet['balance'] - [decimal]$finalWallet['balance']) -eq $chargedVnd
    }
    foreach ($assertion in $summary.assertions.GetEnumerator()) {
        if (-not $assertion.Value) { $failures.Add("Assertion failed: $($assertion.Key)") }
    }
} catch {
    $failures.Add($_.Exception.Message)
} finally {
    $runTimer.Stop()
    $bulk = @($responses | Where-Object phase -eq 'bulk')
    $recovery = @($responses | Where-Object phase -eq 'recovery')
    $probes = @($responses | Where-Object phase -eq 'conflict')
    $summary.actualBulkHttpRequests = $bulk.Count
    $summary.actualRecoveryHttpRequests = $recovery.Count
    $summary.actualConflictHttpRequests = $probes.Count
    $summary.actualHttpRequests = $responses.Count
    $summary.bulkHttpStatusCounts = Status-Counts $bulk
    $summary.recoveryHttpStatusCounts = Status-Counts $recovery
    $summary.httpStatusCounts = Status-Counts $responses
    $summary.requestLatencyMs = Latency-Percentiles $bulk
    $summary.distinctOrders = $latestOrders.Count
    $summary.completedOrders = @($latestOrders.Values | Where-Object { $_['status'] -eq 'COMPLETED' }).Count
    $summary.rejectedOrders = @($latestOrders.Values | Where-Object { $_['status'] -eq 'REJECTED' }).Count
    $summary.pendingOrders = @($latestOrders.Values | Where-Object { $_['status'] -notin @('COMPLETED', 'REJECTED') }).Count
    $reasons = [ordered]@{}
    foreach ($order in @($latestOrders.Values | Where-Object { $_['status'] -eq 'REJECTED' })) {
        $reason = [string]$order['failureCode']
        if (-not $reason) { $reason = 'UNSPECIFIED' }
        if (-not $reasons.Contains($reason)) { $reasons[$reason] = 0 }
        $reasons[$reason]++
    }
    $summary.rejectionReasons = $reasons
    $summary.unacknowledgedLogicalKeys = @($originalByKey.Keys | Where-Object { -not $knownByKey.ContainsKey($_) })
    $summary.totalDurationMs = $runTimer.ElapsedMilliseconds
    $summary.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    $summary.failureMessages = @($failures)
    $summary.status = if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
    Write-JsonFile (Join-Path $OutputDirectory 'orders.final.json') @($latestOrders.Values | Sort-Object { $_['id'] })
    Write-JsonFile (Join-Path $OutputDirectory 'summary.json') $summary
    $accessToken = $null; $password = $null; $login = $null; $config = $null
}

# Emit a machine-readable object; persist all evidence before signalling failure.
[pscustomobject]$summary
if ($failures.Count -gt 0) { throw "Batch run failed; inspect $OutputDirectory/summary.json" }
