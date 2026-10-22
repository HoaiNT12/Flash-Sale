# PowerShell 7+: run on an idle isolated stack, with demo faults RESET.
param([int]$Requests = 200, [int]$Concurrency = 32, [string]$Password = 'Demo@123', [int]$TimeoutSeconds = 240)
$ErrorActionPreference = 'Stop'
$PSDefaultParameterValues = @{'Invoke-RestMethod:TimeoutSec' = 10}
if ($Requests -lt 1 -or $Concurrency -lt 1 -or $Concurrency -gt 128) { throw 'Requests > 0; Concurrency must be 1..128' }
$login = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/auth/login' -ContentType 'application/json' -Body (@{username='alice';password=$Password} | ConvertTo-Json)
$token = $login.accessToken; $headers = @{Authorization="Bearer $token"}
$product = '11111111-1111-1111-1111-111111111111'; $run = [guid]::NewGuid().ToString()
$baseline = Invoke-RestMethod -Uri "http://localhost:8082/api/products/$product" -Headers $headers
$walletBefore = Invoke-RestMethod -Uri 'http://localhost:8083/api/wallets/me' -Headers $headers
$timer = [Diagnostics.Stopwatch]::StartNew()
$results = 1..$Requests | ForEach-Object -ThrottleLimit $Concurrency -Parallel {
    $headers = @{Authorization="Bearer $using:token";'Idempotency-Key'="load-$using:run-$_"}
    try {
        $order = Invoke-RestMethod -TimeoutSec 10 -Method Post -Uri 'http://localhost:8084/api/orders' -Headers $headers -ContentType 'application/json' -Body (@{productId=$using:product;quantity=1} | ConvertTo-Json)
        [pscustomobject]@{id=$order.id;http=202;error=$null}
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        [pscustomobject]@{id=$null;http=$status;error=$_.Exception.Message}
    }
}
$timer.Stop(); $admissionMs = $timer.ElapsedMilliseconds
$pending = @{}; $finished = @{}
foreach ($result in $results) { if ($result.id) { $pending[$result.id] = $true } }
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
while ($pending.Count -gt 0 -and [DateTime]::UtcNow -lt $deadline) {
    foreach ($id in @($pending.Keys)) {
        $order = Invoke-RestMethod -Uri "http://localhost:8084/api/orders/$id" -Headers $headers
        if ($order.status -in @('COMPLETED','REJECTED')) { $finished[$id] = $order; $pending.Remove($id) }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Milliseconds 500 }
}
if ($pending.Count -gt 0) { throw "$($pending.Count) orders still processing; inspect orders/order_history and Circuit Breaker" }
$completed = @($finished.Values | Where-Object status -eq 'COMPLETED')
$rejected = @($finished.Values | Where-Object status -eq 'REJECTED')
$charged = [decimal]0; foreach ($order in $completed) { $charged += [decimal]$order.amount }
$walletAfter = Invoke-RestMethod -Uri 'http://localhost:8083/api/wallets/me' -Headers $headers
if ($completed.Count -gt $baseline.availableStock) { throw 'Overselling detected' }
if ([decimal]$walletBefore.balance - [decimal]$walletAfter.balance -ne $charged) { throw 'Wallet/finished-order totals mismatch' }
$expectedStock = [int]$baseline.availableStock - $completed.Count
$replicaDeadline = [DateTime]::UtcNow.AddSeconds(15)
do {
    $catalog = Invoke-RestMethod -Uri "http://localhost:8082/api/products/$product" -Headers $headers
    if ($catalog.availableStock -eq $expectedStock) { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $replicaDeadline)
if ($catalog.availableStock -ne $expectedStock) { throw 'Replica did not converge or other buyers changed the baseline' }
$unexpected = @($results | Where-Object { $_.http -notin @(202,429) })
if ($unexpected.Count -gt 0) { throw "Unexpected admission errors: $($unexpected | ConvertTo-Json -Compress)" }
[pscustomobject]@{requests=$Requests;accepted=$finished.Count;completed=$completed.Count;rejected=$rejected.Count;admissionRejected=@($results | Where-Object http -eq 429).Count;submissionMs=$admissionMs;chargedVnd=$charged;finalStock=$catalog.availableStock;assertions='PASS'} | Format-List
