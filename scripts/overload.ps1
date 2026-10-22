# Start stack with docker-compose.demo.yml first. Deliberately creates orders.
param([string]$AdminPassword = 'Admin@123', [string]$CustomerPassword = 'Demo@123', [int]$Orders = 32)
$ErrorActionPreference = 'Stop'
$PSDefaultParameterValues = @{'Invoke-RestMethod:TimeoutSec' = 10}
function Token($Name, $Password) {
    (Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/auth/login' -ContentType 'application/json' -Body (@{username=$Name;password=$Password} | ConvertTo-Json)).accessToken
}
$admin = @{Authorization="Bearer $(Token 'admin' $AdminPassword)"}
$buyer = @{Authorization="Bearer $(Token 'alice' $CustomerPassword)"}
$ids = @(); $run = [guid]::NewGuid().ToString()
try {
    Invoke-RestMethod -Method Post -Uri 'http://localhost:8082/demo/faults' -Headers $admin -ContentType 'application/json' -Body '{"delayMs":1200,"fail":true}' | Out-Null
    for ($i = 0; $i -lt $Orders; $i++) {
        $headers = @{} + $buyer; $headers['Idempotency-Key'] = "overload-$run-$i"
        $created = Invoke-RestMethod -Method Post -Uri 'http://localhost:8084/api/orders' -Headers $headers -ContentType 'application/json' -Body '{"productId":"22222222-2222-2222-2222-222222222222","quantity":1}'
        $ids += $created.id
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(30); $seenOpen = $false
    do {
        $states = Invoke-RestMethod -Uri 'http://localhost:8084/actuator/circuitbreakers' -Headers $admin
        Write-Output ($states | ConvertTo-Json -Depth 10 -Compress)
        if ($states.circuitBreakers.inventory.state -eq 'OPEN') { $seenOpen = $true; break }
        Start-Sleep -Milliseconds 1000
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $seenOpen) { throw 'Did not observe OPEN; inspect /actuator/circuitbreakerevents and configured call thresholds' }
} finally {
    Invoke-RestMethod -Method Post -Uri 'http://localhost:8082/demo/faults' -Headers $admin -ContentType 'application/json' -Body '{"delayMs":0,"fail":false}' | Out-Null
}
$deadline = [DateTime]::UtcNow.AddSeconds(180); $pending = @{}; foreach ($id in $ids) { $pending[$id] = $true }
while ($pending.Count -gt 0 -and [DateTime]::UtcNow -lt $deadline) {
    foreach ($id in @($pending.Keys)) {
        $order = Invoke-RestMethod -Uri "http://localhost:8084/api/orders/$id" -Headers $buyer
        if ($order.status -in @('COMPLETED','REJECTED')) { Write-Output "$id $($order.status) $($order.failureCode)"; $pending.Remove($id) }
    }
    Start-Sleep -Milliseconds 1000
}
if ($pending.Count -gt 0) { throw "$($pending.Count) orders have not recovered/compensated" }
Write-Output 'PASS: observed OPEN, reset faults, orders reached terminal states. Inspect events for HALF_OPEN/CLOSED transitions.'
