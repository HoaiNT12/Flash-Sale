param([string]$Password = 'Demo@123', [int]$TimeoutSeconds = 180)
$ErrorActionPreference = 'Stop'
$PSDefaultParameterValues = @{'Invoke-RestMethod:TimeoutSec' = 10}
function Login([string]$Name) {
    $login = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/auth/login' -ContentType 'application/json' -Body (@{username=$Name;password=$Password} | ConvertTo-Json)
    return @{Authorization="Bearer $($login.accessToken)"}
}
function Checkout($Headers, [string]$Key, [string]$Product) {
    $requestHeaders = @{} + $Headers; $requestHeaders['Idempotency-Key'] = $Key
    return Invoke-RestMethod -Method Post -Uri 'http://localhost:8084/api/orders' -Headers $requestHeaders -ContentType 'application/json' -Body (@{productId=$Product;quantity=1} | ConvertTo-Json)
}
function AwaitOrder($Headers, [string]$Id) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $order = Invoke-RestMethod -Uri "http://localhost:8084/api/orders/$Id" -Headers $Headers
        if ($order.status -in @('COMPLETED','REJECTED')) { return $order }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Order $Id did not finish; latest state $($order.status)/$($order.step)"
}
function ExpectStatus([int]$Expected, [scriptblock]$Action) {
    try { & $Action | Out-Null } catch {
        $actual = [int]$_.Exception.Response.StatusCode
        if ($actual -eq $Expected) { return }
        throw "Expected HTTP $Expected, got $actual"
    }
    throw "Expected HTTP $Expected but request succeeded"
}
$alice = Login 'alice'; $bob = Login 'bob'; $poor = Login 'poor'
$product = '11111111-1111-1111-1111-111111111111'
$stock = Invoke-RestMethod -Uri "http://localhost:8082/api/products/$product" -Headers $alice
if ($stock.availableStock -lt 1) { throw 'Seed product sold out; use an isolated fresh database for smoke test' }
$before = Invoke-RestMethod -Uri 'http://localhost:8083/api/wallets/me' -Headers $alice
$key = "smoke-$([guid]::NewGuid())"
$created = Checkout $alice $key $product
$duplicate = Checkout $alice $key $product
if ($created.id -ne $duplicate.id) { throw 'Idempotency violation: duplicate order created' }
$done = AwaitOrder $alice $created.id
if ($done.status -ne 'COMPLETED') { throw "Expected COMPLETED, got $($done.status):$($done.failureCode)" }
$after = Invoke-RestMethod -Uri 'http://localhost:8083/api/wallets/me' -Headers $alice
if ([decimal]$before.balance - [decimal]$after.balance -ne [decimal]$done.amount) { throw 'Wallet debit did not equal order total' }
ExpectStatus 404 { Invoke-RestMethod -Uri "http://localhost:8084/api/orders/$($done.id)" -Headers $bob }
ExpectStatus 403 { Invoke-RestMethod -Method Post -Uri "http://localhost:8083/internal/payments/$($done.id)/void" -Headers $alice -ContentType 'application/json' -Body '{}' }
ExpectStatus 400 { Invoke-RestMethod -Uri 'http://localhost:8084/api/orders/not-a-uuid' -Headers $alice }
$failed = AwaitOrder $poor (Checkout $poor "poor-$([guid]::NewGuid())" $product).id
if ($failed.status -ne 'REJECTED' -or $failed.failureCode -ne 'INSUFFICIENT_FUNDS') { throw "Unexpected insufficient-funds result: $($failed | ConvertTo-Json -Compress)" }
$expectedStock = [int]$stock.availableStock - 1
$replicaDeadline = [DateTime]::UtcNow.AddSeconds(15)
do {
    $catalog = Invoke-RestMethod -Uri "http://localhost:8082/api/products/$product" -Headers $alice
    if ($catalog.availableStock -eq $expectedStock) { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $replicaDeadline)
if ($catalog.availableStock -ne $expectedStock) { throw 'Inventory did not converge after debit and insufficient-funds compensation' }
Write-Output "PASS: order=$($done.id), one debit=$($done.amount) $($done.currency); duplicate checkout, owner isolation, internal scope, invalid request and payment compensation checked."
