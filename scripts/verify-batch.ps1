#requires -Version 7.3
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AuditDirectory,
    [string]$OutputFile = '',
    [string]$BaselinePhase = 'baseline',
    [string]$FinalPhase = 'final',
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string[]]$RunDirectories
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$auditRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($AuditDirectory)
if ([string]::IsNullOrWhiteSpace($OutputFile)) { $OutputFile = Join-Path $auditRoot 'assertions.json' }
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputFile)
$checks = [Collections.Generic.List[object]]::new()
$stockDeltas = [Collections.Generic.List[object]]::new()
$walletDeltas = [Collections.Generic.List[object]]::new()
$dbOrders = @(); $ledger = @(); $plannedUnique = 0; $httpResponseCount = 0

function Add-Check([string]$Name, [bool]$Passed, $Detail = $null) {
    $checks.Add([ordered]@{ name = $Name; passed = $Passed; detail = $Detail })
}
function Rows($Value) {
    if ($null -ne $Value) { foreach ($row in $Value) { $row } }
}
function Read-Json([string]$Path) {
    $json = Get-Content -LiteralPath $Path -Raw -Encoding utf8
    # Preserve monetary JSON literals before ConvertFrom-Json can turn them into doubles.
    $moneyPattern = '("(?:amount|unit_price|unitPrice|balance|opening_balance_inferred|debited_total_global|refunded_total_global)"\s*:\s*)(-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)'
    $json = [regex]::Replace($json, $moneyPattern, '$1"$2"')
    return ConvertFrom-Json -InputObject $json -AsHashtable -Depth 100
}
function Read-JsonLines([string]$Path) {
    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            $moneyPattern = '("(?:amount|unit_price|unitPrice|balance)"\s*:\s*)(-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)'
            ConvertFrom-Json -InputObject ([regex]::Replace($line, $moneyPattern, '$1"$2"')) -AsHashtable -Depth 100
        }
    }
}
function Money($Value) {
    if ($null -eq $Value) { throw 'A required monetary value is null' }
    return [decimal]::Parse([string]$Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture)
}
function Money-Equal($Left, $Right) {
    if ($null -eq $Left -or $null -eq $Right) { return $null -eq $Left -and $null -eq $Right }
    return (Money $Left) -eq (Money $Right)
}
function Identity([string]$Customer, [string]$Key) { return $Customer + [char]31 + $Key }
function Index-Rows($Values, [string]$Field, [string]$Name) {
    $index = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $duplicates = [Collections.Generic.List[string]]::new()
    foreach ($row in @(Rows $Values)) {
        $key = [string]$row[$Field]
        if ($index.ContainsKey($key)) { $duplicates.Add($key) } else { $index.Add($key, $row) }
    }
    Add-Check "$Name.unique" ($duplicates.Count -eq 0) @($duplicates)
    return ,$index
}
function Require([bool]$Condition, [string]$Reason, $Errors) {
    if (-not $Condition) { $Errors.Add($Reason) }
}

try {
    $baseline = @{}; $final = @{}
    foreach ($kind in @('orders', 'inventory', 'payment', 'replica')) {
        $baseline[$kind] = Read-Json (Join-Path $auditRoot "$BaselinePhase.$kind.json")
        $final[$kind] = Read-Json (Join-Path $auditRoot "$FinalPhase.$kind.json")
    }
    Add-Check 'snapshots.primary_and_read_only_replica' (
        $baseline.orders.is_primary -eq $true -and $baseline.inventory.is_primary -eq $true -and
        $baseline.payment.is_primary -eq $true -and $final.orders.is_primary -eq $true -and
        $final.inventory.is_primary -eq $true -and $final.payment.is_primary -eq $true -and
        $baseline.replica.is_in_recovery -eq $true -and $final.replica.is_in_recovery -eq $true)
    Add-Check 'baseline.fresh_isolated_stack' (
        @(Rows $baseline.orders.orders).Count -eq 0 -and @(Rows $baseline.inventory.reservations).Count -eq 0 -and
        @(Rows $baseline.payment.payments).Count -eq 0 -and @(Rows $baseline.payment.ledger).Count -eq 0)
    Add-Check 'audit.full_project_order_scope' ([string]$final.orders.run_prefix -ceq '')

    $dbOrders = @(Rows $final.orders.orders)
    $reservations = @(Rows $final.inventory.reservations)
    $payments = @(Rows $final.payment.payments)
    $ledger = @(Rows $final.payment.ledger)
    $ordersById = Index-Rows $dbOrders 'id' 'orders.id'
    $reservationById = Index-Rows $reservations 'order_id' 'reservations.order_id'
    $paymentById = Index-Rows $payments 'order_id' 'payments.order_id'
    $ordersByKey = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $duplicateKeys = [Collections.Generic.List[string]]::new()
    foreach ($order in $dbOrders) {
        $key = Identity $order.customer_id $order.idempotency_key
        if ($ordersByKey.ContainsKey($key)) { $duplicateKeys.Add($key) } else { $ordersByKey.Add($key, $order) }
    }
    Add-Check 'orders.unique_customer_idempotency_key' ($duplicateKeys.Count -eq 0) @($duplicateKeys)
    Add-Check 'orders.all_terminal' (
        [int]$final.orders.unfinished -eq 0 -and
        @($dbOrders | Where-Object { $_.step -cne 'FINISHED' -or $_.status -cnotin @('COMPLETED', 'REJECTED') }).Count -eq 0)
    Add-Check 'reservations.no_hidden_orphans' (
        $final.inventory.Contains('global_reservation_count') -and
        [long]$final.inventory.global_reservation_count -eq $reservations.Count -and
        @(Rows $final.inventory.unscoped_order_ids).Count -eq 0)
    Add-Check 'payments.no_hidden_orphans' (
        $final.payment.Contains('global_payment_count') -and $final.payment.Contains('global_ledger_event_count') -and
        [long]$final.payment.global_payment_count -eq $payments.Count -and
        [long]$final.payment.global_ledger_event_count -eq $ledger.Count -and
        @(Rows $final.payment.unscoped_payment_order_ids).Count -eq 0 -and
        @(Rows $final.payment.unscoped_ledger_order_ids).Count -eq 0)
    $orphanIds = @($reservations + $payments + $ledger | Where-Object { -not $ordersById.ContainsKey([string]$_.order_id) } | ForEach-Object { $_.order_id } | Sort-Object -Unique)
    Add-Check 'records.no_exported_orphan_order_ids' ($orphanIds.Count -eq 0) $orphanIds
    Add-Check 'reservations.no_missing_orders' (@(Rows $final.inventory.missing_order_ids).Count -eq 0 -and $reservations.Count -eq $dbOrders.Count)
    Add-Check 'payments.no_missing_orders' (@(Rows $final.payment.missing_order_ids).Count -eq 0 -and $payments.Count -eq $dbOrders.Count)

    $expectedKeys = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($runDirectory in $RunDirectories) {
        $runRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($runDirectory)
        $runName = Split-Path -Leaf $runRoot
        $manifest = Read-Json (Join-Path $runRoot 'input.manifest.json')
        $summary = Read-Json (Join-Path $runRoot 'summary.json')
        $responses = @(Read-JsonLines (Join-Path $runRoot 'responses.jsonl'))
        $requests = @(Read-JsonLines (Join-Path $runRoot 'rawrequests.jsonl'))
        $httpFinal = @(Rows (Read-Json (Join-Path $runRoot 'orders.final.json')))
        $httpResponseCount += $responses.Count
        $plannedUnique += [int]$manifest.uniqueOrders
        Add-Check "run.$runName.status" ([string]$summary.status -ceq 'PASS') $summary.failureMessages
        Add-Check "run.$runName.declared_no_unacknowledged_keys" (@(Rows $summary.unacknowledgedLogicalKeys).Count -eq 0)
        Add-Check "run.$runName.record_counts" (
            $requests.Count -eq $responses.Count -and $responses.Count -eq [int]$summary.actualHttpRequests -and
            @($responses | Where-Object { $_.phase -ceq 'bulk' }).Count -eq [int]$manifest.plannedBulkHttpRequests -and
            @(Rows $manifest.keyMapping).Count -eq [int]$manifest.uniqueOrders)
        $requestByIndex = Index-Rows $requests 'requestIndex' "run.$runName.request_index"
        $accepted = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
        $responseErrors = [Collections.Generic.List[string]]::new()
        foreach ($mapping in @(Rows $manifest.keyMapping)) {
            $identity = Identity $manifest.customer.username $mapping.idempotencyKey
            if ($expectedKeys.ContainsKey($identity)) { $responseErrors.Add("Duplicate planned customer/key $($mapping.idempotencyKey)") }
            else { $expectedKeys.Add($identity, @{run = $runName; product = $manifest.productId; quantity = $manifest.quantity}) }
            if (-not $ordersByKey.ContainsKey($identity)) { $responseErrors.Add("Planned key absent from database: $($mapping.idempotencyKey)") }
        }
        foreach ($response in $responses) {
            $identity = Identity $manifest.customer.username $response.idempotencyKey
            if (-not $expectedKeys.ContainsKey($identity)) { $responseErrors.Add("Unexpected response key $($response.idempotencyKey)") }
            if (-not $requestByIndex.ContainsKey([string]$response.requestIndex)) { $responseErrors.Add("Missing raw request $($response.requestIndex)"); continue }
            $request = $requestByIndex[[string]$response.requestIndex]
            if ([string]$request.idempotencyKey -cne [string]$response.idempotencyKey) { $responseErrors.Add("Request/response key mismatch $($response.requestIndex)") }
            if ($response.phase -ceq 'conflict' -and [int]$response.httpStatus -ne 409) { $responseErrors.Add("Conflict probe did not return409: $($response.requestIndex)") }
            if ([int]$response.httpStatus -ne 202) { continue }
            if ($response.body -isnot [Collections.IDictionary] -or -not $response.body['id']) { $responseErrors.Add("Malformed202 response $($response.requestIndex)"); continue }
            $id = [string]$response.body.id
            if (-not $accepted.ContainsKey($identity)) { $accepted.Add($identity, [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)) }
            [void]$accepted[$identity].Add($id)
            if (-not $ordersById.ContainsKey($id)) { $responseErrors.Add("HTTP order absent from database: $id"); continue }
            $order = $ordersById[$id]
            if ((Identity $order.customer_id $order.idempotency_key) -cne $identity -or
                [string]$order.product_id -cne [string]$request.body.productId -or [int]$order.quantity -ne [int]$request.body.quantity -or
                [string]$response.body.customerId -cne [string]$order.customer_id -or
                [string]$response.body.productId -cne [string]$order.product_id -or [int]$response.body.quantity -ne [int]$order.quantity) {
                $responseErrors.Add("HTTP/database identity or payload mismatch: $id")
            }
        }
        foreach ($mapping in @(Rows $manifest.keyMapping)) {
            $identity = Identity $manifest.customer.username $mapping.idempotencyKey
            if (-not $accepted.ContainsKey($identity) -or $accepted[$identity].Count -ne 1) { $responseErrors.Add("Unacknowledged or multiple-ID key: $($mapping.idempotencyKey)") }
        }
        foreach ($order in $httpFinal) {
            $id = [string]$order.id
            if (-not $ordersById.ContainsKey($id)) { $responseErrors.Add("Final HTTP order absent from database: $id"); continue }
            $db = $ordersById[$id]
            if ([string]$order.status -cne [string]$db.status -or [string]$order.step -cne [string]$db.step -or
                -not (Money-Equal $order.amount $db.amount) -or [string]$order.customerId -cne [string]$db.customer_id) {
                $responseErrors.Add("Final HTTP/database state mismatch: $id")
            }
        }
        Add-Check "run.$runName.http_database_mapping" ($responseErrors.Count -eq 0 -and $httpFinal.Count -eq [int]$manifest.uniqueOrders) @($responseErrors)
        $runOrders = @($dbOrders | Where-Object { [string]$_.customer_id -ceq [string]$manifest.customer.username -and [string]$_.idempotency_key -clike "batch-$($manifest.runId)-*" })
        if ([string]$manifest.customer.username -ceq 'poor' -or [string]$manifest.productId -ceq '33333333-3333-3333-3333-333333333333') {
            Add-Check "run.$runName.expected_all_rejected" (@($runOrders | Where-Object { $_.status -cne 'REJECTED' }).Count -eq 0 -and $runOrders.Count -eq [int]$manifest.uniqueOrders)
        }
    }
    Add-Check 'orders.count_matches_planned_unique' ($dbOrders.Count -eq $plannedUnique) @{planned = $plannedUnique; actual = $dbOrders.Count}
    $unexpectedKeys = @($ordersByKey.Keys | Where-Object { -not $expectedKeys.ContainsKey($_) })
    Add-Check 'orders.no_unplanned_logical_keys' ($unexpectedKeys.Count -eq 0) $unexpectedKeys
    foreach ($order in $dbOrders) {
        $identity = Identity $order.customer_id $order.idempotency_key
        if ($expectedKeys.ContainsKey($identity)) {
            $expected = $expectedKeys[$identity]
            Add-Check "order.$($order.id).planned_payload" ([string]$order.product_id -ceq [string]$expected.product -and [int]$order.quantity -eq [int]$expected.quantity)
        }
    }

    $ledgerByOrder = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($entry in $ledger) {
        $id = [string]$entry.order_id
        if (-not $ledgerByOrder.ContainsKey($id)) { $ledgerByOrder.Add($id, [Collections.Generic.List[object]]::new()) }
        $ledgerByOrder[$id].Add($entry)
    }
    $duplicates = @($ledger | Group-Object { "$($_.order_id)/$($_.event_type)" } | Where-Object Count -gt 1 | ForEach-Object Name)
    Add-Check 'ledger.unique_order_event_type' ($duplicates.Count -eq 0) $duplicates
    Add-Check 'ledger.only_debit_or_refund' (@($ledger | Where-Object { $_.event_type -cnotin @('DEBIT', 'REFUND') }).Count -eq 0)
    foreach ($order in $dbOrders) {
        $id = [string]$order.id; $errors = [Collections.Generic.List[string]]::new()
        $events = if ($ledgerByOrder.ContainsKey($id)) { @($ledgerByOrder[$id].ToArray()) } else { @() }
        $debits = @($events | Where-Object { $_.event_type -ceq 'DEBIT' })
        $refunds = @($events | Where-Object { $_.event_type -ceq 'REFUND' })
        Require ($reservationById.ContainsKey($id)) 'Missing reservation' $errors
        Require ($paymentById.ContainsKey($id)) 'Missing payment' $errors
        if ($reservationById.ContainsKey($id) -and $paymentById.ContainsKey($id)) {
            $reservation = $reservationById[$id]; $payment = $paymentById[$id]
            if ($order.status -ceq 'COMPLETED') {
                Require ($reservation.status -ceq 'CONFIRMED' -and $payment.status -ceq 'CHARGED') 'Completed downstream states differ' $errors
                Require ($debits.Count -eq 1 -and $refunds.Count -eq 0) 'Completed requires exactly1debit/0refund' $errors
                Require ($null -ne $order.amount -and $null -ne $order.unit_price -and $null -ne $reservation.unit_price -and $null -ne $payment.amount) 'Completed amount/price missing' $errors
                if ($null -ne $order.amount -and $null -ne $order.unit_price) {
                    Require ((Money $order.amount) -eq (Money $order.unit_price) * [decimal]$order.quantity) 'Order multiplication differs' $errors
                    Require ((Money-Equal $order.unit_price $reservation.unit_price) -and (Money-Equal $order.amount $payment.amount)) 'Order/reservation/payment money differs' $errors
                }
                Require ([string]$order.currency -ceq [string]$reservation.currency -and [string]$order.currency -ceq [string]$payment.currency -and [string]$payment.customer_id -ceq [string]$order.customer_id) 'Payment customer/currency differs' $errors
            } elseif ($order.status -ceq 'REJECTED') {
                Require ($reservation.status -ceq 'CANCELLED' -and $payment.status -ceq 'VOIDED') 'Rejected downstream states differ' $errors
                Require (($debits.Count -eq 0 -and $refunds.Count -eq 0) -or ($debits.Count -eq 1 -and $refunds.Count -eq 1)) 'Rejected debit/refund counts differ' $errors
                if ($debits.Count -eq 1 -and $refunds.Count -eq 1) { Require (Money-Equal $debits[0].amount $refunds[0].amount) 'Compensation refund differs from debit' $errors }
            } else { $errors.Add('Order is not terminal') }
            if ($null -ne $reservation.product_id) {
                Require ([string]$reservation.product_id -ceq [string]$order.product_id -and [int]$reservation.quantity -eq [int]$order.quantity) 'Reservation payload differs' $errors
                if ($null -ne $order.unit_price) { Require (Money-Equal $reservation.unit_price $order.unit_price) 'Stored reservation quote differs' $errors }
            }
            if ($null -ne $payment.customer_id) {
                Require ([string]$payment.customer_id -ceq [string]$order.customer_id -and [string]$payment.currency -ceq [string]$order.currency -and (Money-Equal $payment.amount $order.amount)) 'Stored payment payload differs' $errors
            }
            foreach ($entry in $events) {
                Require ([string]$entry.customer_id -ceq [string]$order.customer_id -and [string]$entry.currency -ceq [string]$order.currency -and (Money-Equal $entry.amount $order.amount)) 'Ledger amount/customer/currency differs from order' $errors
            }
        }
        Add-Check "order.$id.reconciled" ($errors.Count -eq 0) @{status = $order.status; debits = $debits.Count; refunds = $refunds.Count; errors = @($errors)}
    }

    $beforeProducts = Index-Rows $baseline.inventory.products 'id' 'baseline.products'
    $afterProducts = Index-Rows $final.inventory.products 'id' 'final.products'
    $replicaProducts = Index-Rows $final.replica.products 'id' 'replica.products'
    Add-Check 'products.primary_and_replica_row_counts' ($beforeProducts.Count -eq $afterProducts.Count -and $afterProducts.Count -eq $replicaProducts.Count)
    foreach ($product in @(Rows $final.inventory.products)) {
        $id = [string]$product.id; $units = 0
        foreach ($order in $dbOrders) { if ($order.status -ceq 'COMPLETED' -and [string]$order.product_id -ceq $id) { $units += [int]$order.quantity } }
        $exists = $beforeProducts.ContainsKey($id) -and $replicaProducts.ContainsKey($id)
        Add-Check "product.$id.exists" $exists
        if (-not $exists) { continue }
        $before = $beforeProducts[$id]; $replica = $replicaProducts[$id]
        $delta = [int]$before.available_stock - [int]$product.available_stock
        Add-Check "product.$id.stock_conservation" ([int]$product.available_stock -ge 0 -and (Money $before.conservation_total) -eq (Money $product.conservation_total) -and $delta -eq $units -and [int]$product.held_quantity_global -eq $units)
        Add-Check "product.$id.replica_converged" ([int]$replica.available_stock -eq [int]$product.available_stock -and (Money-Equal $replica.unit_price $product.unit_price) -and [string]$replica.currency -ceq [string]$product.currency)
        $stockDeltas.Add(@{productId = $id; baseline = [int]$before.available_stock; final = [int]$product.available_stock; difference = $delta; completedUnits = $units})
    }
    $beforeWallets = Index-Rows $baseline.payment.wallets 'customer_id' 'baseline.wallets'
    $afterWallets = Index-Rows $final.payment.wallets 'customer_id' 'final.wallets'
    Add-Check 'wallets.row_count_unchanged' ($beforeWallets.Count -eq $afterWallets.Count)
    foreach ($wallet in @(Rows $final.payment.wallets)) {
        $customer = [string]$wallet.customer_id
        Add-Check "wallet.$customer.existed_at_baseline" ($beforeWallets.ContainsKey($customer))
        if (-not $beforeWallets.ContainsKey($customer)) { continue }
        $before = $beforeWallets[$customer]; $debits = [decimal]0; $refunds = [decimal]0; $completedAmount = [decimal]0
        foreach ($entry in $ledger) {
            if ([string]$entry.customer_id -cne $customer) { continue }
            if ($entry.event_type -ceq 'DEBIT') { $debits += Money $entry.amount } else { $refunds += Money $entry.amount }
        }
        foreach ($order in $dbOrders) { if ($order.status -ceq 'COMPLETED' -and [string]$order.customer_id -ceq $customer) { $completedAmount += Money $order.amount } }
        $delta = (Money $before.balance) - (Money $wallet.balance)
        Add-Check "wallet.$customer.exact_money_conservation" ((Money $wallet.balance) -ge 0 -and (Money-Equal $before.opening_balance_inferred $wallet.opening_balance_inferred) -and $delta -eq ($debits - $refunds) -and $delta -eq $completedAmount -and ((Money $wallet.debited_total_global) - (Money $before.debited_total_global)) -eq $debits -and ((Money $wallet.refunded_total_global) - (Money $before.refunded_total_global)) -eq $refunds)
        $walletDeltas.Add(@{customer = $customer; baseline = Money $before.balance; final = Money $wallet.balance; difference = $delta; debits = $debits; refunds = $refunds; completedAmount = $completedAmount})
    }
} catch {
    Add-Check 'verification.completed_without_artifact_or_processing_error' $false $_.Exception.Message
}

$failed = @($checks | Where-Object { -not $_.passed })
$status = if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' }
$report = [ordered]@{
    status = $status; summary = $status; generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    baselinePhase = $BaselinePhase; finalPhase = $FinalPhase; auditDirectory = $auditRoot
    totals = @{plannedUniqueOrders = $plannedUnique; databaseOrders = $dbOrders.Count; completedOrders = @($dbOrders | Where-Object { $_.status -ceq 'COMPLETED' }).Count; rejectedOrders = @($dbOrders | Where-Object { $_.status -ceq 'REJECTED' }).Count; httpResponses = $httpResponseCount; ledgerEvents = $ledger.Count; checks = $checks.Count; failedChecks = $failed.Count}
    stockDeltas = @($stockDeltas); walletDeltas = @($walletDeltas); checks = @($checks); failedChecks = $failed
}
New-Item -ItemType Directory -Path (Split-Path -Parent $outputPath) -Force | Out-Null
[IO.File]::WriteAllText($outputPath, (ConvertTo-Json -InputObject $report -Depth 30) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
[pscustomobject]@{status = $status; checks = $checks.Count; failedChecks = $failed.Count; outputFile = $outputPath} | ConvertTo-Json -Compress
if ($failed.Count -gt 0) { throw "Batch verification failed; inspect $outputPath" }
