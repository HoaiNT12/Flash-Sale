# Chạy FlashSale từ đầu bằng Windows PowerShell 5.1

Hướng dẫn này dùng **Windows PowerShell 5.1**, Docker Desktop chạy Linux containers và các script đi kèm project. Không cần cài PowerShell 7. Nếu chạy toàn bộ ứng dụng bằng Docker, không cần cài Java hoặc Maven trên Windows: Dockerfile đã có bước build bằng Maven và JDK 21.

## 1. Mở đúng PowerShell và thư mục project

Mở **Windows PowerShell** từ Start. Nếu terminal hiện tại đang dùng PowerShell 7, chạy lệnh sau để mở phiên 5.1 ngay trong terminal:

```powershell
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile
```

Trong phiên vừa mở:

```powershell
$PSVersionTable.PSVersion
$ErrorActionPreference = 'Stop'
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force

Set-Location 'C:\Users\Admin\OneDrive\Documents\java\FlashSale\FlashSale'
Test-Path -LiteralPath '.\pom.xml'
Test-Path -LiteralPath '.\docker-compose.yml'
```

Phiên bản cần hiện `Major = 5`, `Minor = 1`; hai lệnh `Test-Path` đều phải trả `True`. Thư mục Maven nằm **bên trong** thư mục workspace `FlashSale`, nên đường dẫn hiện tại có hai đoạn `FlashSale\FlashSale`.

`-Scope Process` chỉ áp dụng execution policy cho phiên PowerShell đang mở. Xem [hướng dẫn Microsoft](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/set-executionpolicy?view=powershell-5.1). Không cần mở terminal bằng quyền Administrator cho các bước chạy project dưới đây.

## 2. Kiểm tra Docker Desktop

Mở Docker Desktop, chờ engine sẵn sàng và dùng Linux containers. Sau đó chạy:

```powershell
docker version
if ($LASTEXITCODE -ne 0) { throw 'Docker engine chưa sẵn sàng. Mở Docker Desktop rồi thử lại.' }

docker info --format '{{.OSType}}'
if ($LASTEXITCODE -ne 0) { throw 'Không kết nối được Docker engine.' }

docker compose version
if ($LASTEXITCODE -ne 0) { throw 'Chưa có Docker Compose plugin.' }
```

`OSType` phải là `linux`. Phần test batch dùng `!override`, cần Docker Compose **2.24.4 trở lên**; các bản Compose mới hơn cũng hỗ trợ. Xem [tài liệu Docker](https://docs.docker.com/reference/compose-file/merge/).

Các cổng mặc định của ứng dụng là `8081`–`8084`, PostgreSQL primary `5432` và replica `5433`. Nếu cổng đã có ứng dụng khác sử dụng, xem phần xử lý lỗi ở cuối tài liệu.

## 3. Tạo cấu hình và build ứng dụng trong Docker

Chỉ sao chép cấu hình mẫu nếu chưa có `.env`, để giữ cấu hình đã dùng trước đó:

```powershell
if (-not (Test-Path -LiteralPath '.\.env')) {
    Copy-Item -LiteralPath '.\.env.example' -Destination '.\.env'
}

docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Cấu hình Docker Compose không hợp lệ.' }

docker compose build
if ($LASTEXITCODE -ne 0) { throw 'Build Docker image thất bại. Xem lỗi Maven hoặc tải image phía trên.' }
```

Lần đầu Docker tải base images và Maven dependencies nên có thể mất vài phút. Bước build tạo image cho Auth, Inventory, Payment và Order. Dockerfile dùng `-DskipTests`; bước này **chưa chạy các bài kiểm thử Java**. Phần 9 hướng dẫn chạy bộ kiểm thử riêng nếu cần.

## 4. Khởi động và kiểm tra sáu container

```powershell
docker compose up -d --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) {
    docker compose ps --all
    docker compose logs --tail=100
    throw 'Có container chưa healthy. Xem logs trước khi gọi API.'
}

docker compose ps
```

Kỳ vọng có sáu container đang chạy và healthy: `postgres-primary`, `postgres-replica`, `auth-service`, `inventory-service`, `payment-service`, `order-service`.

Kiểm tra HTTP:

```powershell
foreach ($port in @(8081, 8082, 8083, 8084)) {
    $health = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 10
    [pscustomobject]@{ Port = $port; Status = $health.status }
}
```

Cả bốn API phải trả `UP`. Các ví dụ dùng `127.0.0.1` để gọi trực tiếp địa chỉ IPv4 đã bind. Claim issuer của JWT vẫn theo cấu hình `.env`, mặc định `http://localhost:8081`.

## 5. Chạy smoke test

Smoke test tạo đơn thật trên dữ liệu demo, kiểm tra gửi lặp, quyền truy cập, trừ ví và hoàn tác khi thiếu tiền:

```powershell
.\scripts\smoke.ps1
```

Kỳ vọng dòng cuối bắt đầu bằng `PASS:`. Trên database mới, smoke dùng thành công một sản phẩm đầu tiên, nên tồn kho còn `99`. Không chạy smoke và load cùng lúc. Nếu đã mua hết hàng ở các lần trước, dùng stack batch riêng ở phần 8 để có dữ liệu mới.

Tài khoản mẫu khi dùng `.env.example`: `alice`, `bob`, `poor` có mật khẩu `Demo@123`; `admin` có mật khẩu `Admin@123`. `poor` có ví bằng 0. Nếu đã thay mật khẩu seed khi tạo database lần đầu, dùng tham số `-Password` tương ứng khi chạy smoke.

## 6. Đăng nhập, mua hàng và theo dõi kết quả bằng tay

Chạy các lệnh trong **cùng một phiên PowerShell** để giữ biến token, mã đơn và idempotency key. Nếu đã tùy chỉnh mật khẩu seed, thay `Demo@123` trong `$loginBody` bằng mật khẩu đã tạo database:

```powershell
$loginBody = @{ username = 'alice'; password = 'Demo@123' } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/auth/login' -ContentType 'application/json' -Body $loginBody -TimeoutSec 10
$buyerHeaders = @{ Authorization = "Bearer $($login.accessToken)" }

Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8082/api/products' -Headers $buyerHeaders -TimeoutSec 10
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8083/api/wallets/me' -Headers $buyerHeaders -TimeoutSec 10

$idempotencyKey = 'manual-' + [guid]::NewGuid().ToString('N')
$orderHeaders = @{
    Authorization = "Bearer $($login.accessToken)"
    'Idempotency-Key' = $idempotencyKey
}
$orderBody = @{
    productId = '11111111-1111-1111-1111-111111111111'
    quantity = 1
} | ConvertTo-Json -Compress

$accepted = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8084/api/orders' -Headers $orderHeaders -ContentType 'application/json' -Body $orderBody -TimeoutSec 10
$accepted | Format-List

$deadline = [DateTime]::UtcNow.AddSeconds(180)
do {
    $order = Invoke-RestMethod -Method Get -Uri ("http://127.0.0.1:8084/api/orders/{0}" -f $accepted.id) -Headers $buyerHeaders -TimeoutSec 10
    if ($order.status -in @('COMPLETED', 'REJECTED')) { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)

$order | Format-List id, status, step, amount, currency, failureCode
if ($order.status -notin @('COMPLETED', 'REJECTED')) {
    throw 'Đơn chưa kết thúc trong thời gian chờ. Kiểm tra logs và truy vấn lại bằng cùng order ID.'
}
```

POST trả `202` nghĩa là đã nhận đơn; kết quả cuối lấy từ GET. Với hàng còn đủ và ví alice đủ tiền, kỳ vọng `COMPLETED`, số tiền `99000 VND`. Nếu hết hàng, đơn trả `REJECTED` với mã `OUT_OF_STOCK`.

Gửi lại đúng lần mua vừa rồi bằng **cùng headers và body**, không tạo key mới:

```powershell
$again = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8084/api/orders' -Headers $orderHeaders -ContentType 'application/json' -Body $orderBody -TimeoutSec 10
$again.id -eq $accepted.id
```

Kỳ vọng `True`. Khi mất kết nối hoặc nhận `503/429`, giữ nguyên key và body cho lần thử lại. JWT hết hạn sau 15 phút; đăng nhập lại để lấy token mới nhưng vẫn giữ key/body của lần mua cần tra cứu hoặc gửi lại.

Nếu token hết hạn trong lúc demo, đăng nhập lại và cập nhật **cả hai bộ headers**, giữ nguyên `$idempotencyKey` và `$orderBody`:

```powershell
$login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/auth/login' -ContentType 'application/json' -Body $loginBody -TimeoutSec 10
$buyerHeaders.Authorization = "Bearer $($login.accessToken)"
$orderHeaders.Authorization = "Bearer $($login.accessToken)"
```

## 7. Kiểm tra PostgreSQL replica

Không cần cài `psql` trên Windows. Chạy client bên trong container:

```powershell
docker compose exec -T postgres-primary psql -U postgres -d postgres -c "SELECT application_name,state,sync_state FROM pg_stat_replication;"
if ($LASTEXITCODE -ne 0) { throw 'Không đọc được trạng thái replication.' }

docker compose exec -T postgres-replica psql -U postgres -d postgres -c "SELECT pg_is_in_recovery();"
if ($LASTEXITCODE -ne 0) { throw 'Không truy vấn được replica.' }

docker compose exec -T postgres-primary psql -U postgres -d inventorydb -c "SELECT id,available_stock FROM products ORDER BY id;"
docker compose exec -T postgres-replica psql -U postgres -d inventorydb -c "SELECT id,available_stock FROM products ORDER BY id;"
```

Kỳ vọng replica ở trạng thái `streaming`, `sync_state = async`, `pg_is_in_recovery = t`. Sau khi bắt kịp WAL, tồn kho đọc ở replica khớp primary. Quyết định giữ hàng và trừ tiền luôn dùng primary. Replica hiện chưa tự động failover.

## 8. Gửi hàng loạt order hoặc mô phỏng quá tải

### Batch test có input, output và SQL audit

Dùng [BATCH_ORDER_TEST.md](BATCH_ORDER_TEST.md#chạy-lại-từ-đầu) để dựng **Compose project mới** trên cổng `18081`–`18084` và `15432`–`15433`, gửi 200 ý định mua cùng 100 request lặp, rồi đối soát tồn kho, ví và ledger. Quy trình này cũng chạy bằng PowerShell 5.1. Không chạy smoke trên stack batch trước lượt chính nếu muốn giữ baseline 100 sản phẩm.

### Chạy thêm thiếu tiền và hàng đã hết

Các khối lệnh dưới đây chạy trên **stack mặc định ở phần 4**, API cổng `8081`–`8084`. Mỗi khối tự tạo URL và thư mục output mới, nên có thể copy riêng khối thiếu tiền hoặc soldout vào một phiên PowerShell mới. Không cần chạy `test-order-scenarios.ps1`, không phụ thuộc biến từ khối trước.

Đứng trong thư mục `FlashSale\FlashSale`, copy nguyên khối gồm cả `& {` và dấu `}` cuối cùng. Bọc trong khối giúp lệnh chạy theo thứ tự và dừng nếu gặp lỗi. Nếu PowerShell hiện `>>` khi đang dán, đó là dấu chờ hoàn tất khối; không copy thêm các ký tự `PS ...>` hoặc `>>` từ terminal. Không chạy load/demo fault cùng lúc.

Các lệnh dùng `batch-orders.ps1` để gửi 10 ý định mua và 10 request lặp, đồng thời lưu request/response và kiểm tra tồn kho, ví. Không cần chạy batch 200 đơn trước. Nếu mật khẩu seed khác, đổi `Demo@123` trong input của khối tương ứng. Nếu đổi cổng Docker, cập nhật đủ bốn URL.

**Thiếu tiền:** poor có ví 0, mua SKU thứ hai. Cần ít nhất 10 sản phẩm khả dụng cho lượt đồng thời này. Toàn bộ cấu hình nằm trong một khối có thể copy độc lập:

```powershell
& {
    $ErrorActionPreference = 'Stop'
    Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
    if (-not (Test-Path -LiteralPath '.\scripts\batch-orders.ps1')) { throw 'Hay vao dung thu muc FlashSale\FlashSale.' }
    $poor = Get-Content -LiteralPath '.\examples\batch-orders.input.json' -Raw -Encoding UTF8 | ConvertFrom-Json
    $poor.baseUrls = [pscustomobject]@{
        auth = 'http://127.0.0.1:8081'
        inventory = 'http://127.0.0.1:8082'
        payment = 'http://127.0.0.1:8083'
        order = 'http://127.0.0.1:8084'
    }
    $poor.customer.username = 'poor'
    $poor.customer.password = 'Demo@123'
    $poor.productId = '22222222-2222-2222-2222-222222222222'
    $poor.quantity = 1
    $poor.uniqueOrders = 10
    $poor.duplicateRequests = 10
    $poor.concurrency = 16

    $loginBody = $poor.customer | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Method Post -Uri "$($poor.baseUrls.auth)/api/auth/login" -ContentType 'application/json' -Body $loginBody -TimeoutSec 10
    $headers = @{ Authorization = "Bearer $($login.accessToken)" }
    $stock = Invoke-RestMethod -Uri "$($poor.baseUrls.inventory)/api/products/$($poor.productId)" -Headers $headers -TimeoutSec 10
    $wallet = Invoke-RestMethod -Uri "$($poor.baseUrls.payment)/api/wallets/me" -Headers $headers -TimeoutSec 10
    if ([decimal]$wallet.balance -ne 0 -or [int]$stock.availableStock -lt 10) { throw 'Can vi poor = 0 va SKU 2 con it nhat 10 san pham. Chua gui don.' }

    $poorOut = Join-Path (Get-Location) ('test-results/poor-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Path $poorOut -ErrorAction Stop | Out-Null
    $poor | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$poorOut/input.json" -Encoding UTF8
    Write-Host "Output: $poorOut"
    .\scripts\batch-orders.ps1 -InputFile "$poorOut/input.json" -OutputDirectory "$poorOut/run"
    $result = Get-Content -LiteralPath "$poorOut/run/summary.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($result.status -ne 'PASS' -or $result.distinctOrders -ne 10 -or $result.rejectedOrders -ne 10 -or $result.rejectionReasons.INSUFFICIENT_FUNDS -ne 10) { throw "Khong dung ky vong INSUFFICIENT_FUNDS. Xem $poorOut/run/summary.json" }
    $result | Select-Object status, distinctOrders, rejectedOrders, rejectionReasons, observedConsistency | ConvertTo-Json -Depth 6
}
```

**Hàng đã hết:** Alice mua SKU thứ ba được seed với tồn kho 0. Khối này cũng chạy độc lập; không cần chạy khối thiếu tiền trước:

```powershell
& {
    $ErrorActionPreference = 'Stop'
    Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
    if (-not (Test-Path -LiteralPath '.\scripts\batch-orders.ps1')) { throw 'Hay vao dung thu muc FlashSale\FlashSale.' }
    $soldout = Get-Content -LiteralPath '.\examples\batch-orders.input.json' -Raw -Encoding UTF8 | ConvertFrom-Json
    $soldout.baseUrls = [pscustomobject]@{
        auth = 'http://127.0.0.1:8081'
        inventory = 'http://127.0.0.1:8082'
        payment = 'http://127.0.0.1:8083'
        order = 'http://127.0.0.1:8084'
    }
    $soldout.customer.username = 'alice'
    $soldout.customer.password = 'Demo@123'
    $soldout.productId = '33333333-3333-3333-3333-333333333333'
    $soldout.quantity = 1
    $soldout.uniqueOrders = 10
    $soldout.duplicateRequests = 10
    $soldout.concurrency = 16

    $loginBody = $soldout.customer | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Method Post -Uri "$($soldout.baseUrls.auth)/api/auth/login" -ContentType 'application/json' -Body $loginBody -TimeoutSec 10
    $headers = @{ Authorization = "Bearer $($login.accessToken)" }
    $stock = Invoke-RestMethod -Uri "$($soldout.baseUrls.inventory)/api/products/$($soldout.productId)" -Headers $headers -TimeoutSec 10
    if ([int]$stock.availableStock -ne 0) { throw 'Can SKU 3 co ton kho = 0. Chua gui don.' }

    $soldoutOut = Join-Path (Get-Location) ('test-results/soldout-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Path $soldoutOut -ErrorAction Stop | Out-Null
    $soldout | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$soldoutOut/input.json" -Encoding UTF8
    Write-Host "Output: $soldoutOut"
    .\scripts\batch-orders.ps1 -InputFile "$soldoutOut/input.json" -OutputDirectory "$soldoutOut/run"
    $result = Get-Content -LiteralPath "$soldoutOut/run/summary.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($result.status -ne 'PASS' -or $result.distinctOrders -ne 10 -or $result.rejectedOrders -ne 10 -or $result.rejectionReasons.OUT_OF_STOCK -ne 10) { throw "Khong dung ky vong OUT_OF_STOCK. Xem $soldoutOut/run/summary.json" }
    $result | Select-Object status, distinctOrders, rejectedOrders, rejectionReasons, observedConsistency | ConvertTo-Json -Depth 6
}
```

| Kiểm tra | Thiếu tiền (`poor`) | Hàng đã hết (`soldout`) |
|---|---|---|
| Ý định mua / request lặp | 10 / 10 | 10 / 10 |
| Kết quả cuối | 10 `REJECTED`, mã `INSUFFICIENT_FUNDS` | 10 `REJECTED`, mã `OUT_OF_STOCK` |
| Tồn SKU được kiểm tra | Trở về mức trước test sau bù trừ | Giữ nguyên 0 |
| Ví sau test | Ví poor vẫn 0 | Ví Alice không đổi |
| Probe cùng key, khác body | HTTP 409 | HTTP 409 |

Mỗi khối gửi 20 POST bulk và thêm một probe 409; recovery POST khi gặp lỗi tạm thời được đếm riêng. HTTP 202 chỉ xác nhận nhận đơn, kết quả cuối lấy từ GET. `PASS` nghĩa là bị từ chối đúng nguyên nhân và các kiểm tra HTTP, idempotency, tồn kho, số dư của batch đều đạt.

Dòng `Output:` cho biết thư mục kết quả. File `input.json` là cấu hình đã tạo (có mật khẩu demo); thư mục con `run` chứa manifest không kèm credentials, request/response, polling, snapshot và summary. `observedConsistency` trong summary cho biết tồn kho và số dư trước/sau. Mỗi lần copy chạy lại tạo output và idempotency key mới.

Lỗi cũ `Access to C:\soldout.input.json is denied` xảy ra vì `$scenarioOut` rỗng. Các khối mới tự tạo `$poorOut` hoặc `$soldoutOut` bên trong project trước khi ghi file, không dùng biến đó nữa và không cần quyền Administrator.

Hai lượt thêm tổng cộng 20 đơn REJECTED vào stack hiện có, không reset database. Nếu lỗi, xem summary và `failureMessages` tại đường dẫn output đã in. Phần này đối chiếu qua API; để audit SQL, ledger, orphan và replica trên stack mới, dùng [BATCH_ORDER_TEST.md](BATCH_ORDER_TEST.md).

### Load nhanh trên stack mặc định

```powershell
.\scripts\load.ps1 -Requests 20 -Concurrency 4
```

Load dùng tồn kho và số dư ở thời điểm bắt đầu làm baseline. Nếu đã chạy smoke và mua một đơn bằng tay trên database mới, SKU đầu còn 98 sản phẩm, nên không kỳ vọng 100 đơn thành công. `load.ps1` báo lỗi nếu gặp admission HTTP 503; script này không tự recovery. Dùng batch test riêng nếu muốn tạo tải 200 đơn với concurrency 32, lưu đầy đủ input/output, phân biệt lỗi HTTP tạm thời và thực hiện recovery.

### Demo Circuit Breaker

```powershell
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { throw 'Chưa khởi động được cấu hình demo.' }

.\scripts\overload.ps1
```

Script bật lỗi có kiểm soát trên Inventory, quan sát circuit `OPEN`, reset fault trong `finally` và chờ các đơn hoàn thành hoặc hoàn tác. Để trở về cấu hình mặc định sau demo:

```powershell
docker compose -f docker-compose.yml up -d --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { throw 'Chưa khôi phục được cấu hình mặc định.' }
```

Nếu muốn kiểm tra bằng giao diện hoặc CLI của JMeter, dùng [hướng dẫn Circuit Breaker với JMeter](JMETER_CIRCUIT_BREAKER.md) và Test Plan đi kèm. Bộ hướng dẫn này cũng dùng PowerShell 5.1.

## 9. Tùy chọn: chạy build và tests Java trên Windows

Phần này cần **JDK 21** trên host và Docker engine đang chạy cho Testcontainers. Chạy toàn bộ ứng dụng bằng Docker ở các phần trên không cần bước này.

```powershell
java -version
$env:JAVA_HOME
.\mvnw.cmd -v
if ($LASTEXITCODE -ne 0) { throw 'Maven Wrapper chưa chạy được. Kiểm tra JAVA_HOME và kết nối tải Maven.' }

.\mvnw.cmd -B -ntp verify
if ($LASTEXITCODE -ne 0) { throw 'Build hoặc tests thất bại. Xem target\surefire-reports của từng module.' }
```

Nếu máy có nhiều JDK, đặt `JAVA_HOME` trỏ đến thư mục cài JDK 21 trong phiên hiện tại rồi thêm `$env:JAVA_HOME\bin` vào đầu `PATH` trước khi chạy Maven Wrapper. Không cần cài Maven riêng. Kiểm tra phần `Skipped` của kết quả test: integration test bị skip vì Docker chưa sẵn sàng không phải là đã kiểm thử PostgreSQL thành công.

## 10. Xem logs, dừng và chạy tiếp

```powershell
docker compose logs --tail=100 order-service inventory-service payment-service
docker compose logs -f --tail=100 order-service
```

`Ctrl+C` kết thúc theo dõi logs. Để dừng container và giữ dữ liệu:

```powershell
docker compose stop
```

Để chạy tiếp với dữ liệu đã có:

```powershell
docker compose up -d --wait --wait-timeout 240
```

`docker compose down` xóa container/network nhưng **giữ named volumes**, vì vậy khởi động lại vẫn có đơn hàng, tồn kho và số dư cũ. `docker compose up --build` chỉ build lại ứng dụng, không reset database. Chỉnh `.env` cũng không tự cập nhật dữ liệu đã seed. Để demo lại dữ liệu ban đầu, chọn một trong hai cách bên dưới.

### 10.1. Reset dữ liệu của stack hiện tại để demo lại

**Cách này xóa toàn bộ dữ liệu demo của Compose project hiện tại:** bốn database trong `primary-data`, bản sao trong `replica-data` và khóa ký JWT trong `auth-keys`. Các token cũ không còn dùng được; đăng nhập lại sau khi khởi động. Chỉ dùng khi bạn không cần giữ dữ liệu hiện tại. Không thực hiện trên database cần bảo toàn.

Chạy tại thư mục `FlashSale\FlashSale`, với cùng cấu hình project đã dùng khi tạo stack. Kiểm tra tên project trước; mặc định trong file Compose là `flashsale`. Biến môi trường `COMPOSE_PROJECT_NAME` hoặc tùy chọn `-p` có thể thay tên này:

```powershell
$ErrorActionPreference = 'Stop'
docker compose config --format json | ConvertFrom-Json | Select-Object name
if ($LASTEXITCODE -ne 0) { throw 'Khong doc duoc Compose project. Dung lai truoc khi reset.' }
docker compose ps --all
```

Sau khi đã xác định đúng project cần reset, chạy lần lượt:

```powershell
docker compose down --volumes
if ($LASTEXITCODE -ne 0) { throw 'Chua xoa xong stack/volumes. Xem loi truoc khi tao lai.' }

docker compose up -d --build --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) {
    docker compose ps --all
    docker compose logs --tail=100
    throw 'Stack chua healthy sau khi reset.'
}

docker compose ps
```

Volume mới khiến PostgreSQL chạy lại phần khởi tạo database; Flyway tạo bảng và nạp seed, replica được bootstrap lại từ primary. Cần tạo lại **cả primary và replica** cùng nhau, không giữ replica chứa dữ liệu cũ khi thay primary mới. `down --volumes` ở trên xử lý các volume của đúng Compose project, không cần dùng `docker volume prune` hoặc `docker system prune`.

Nếu stack trước đó được tạo bằng `docker compose -p <ten-project> ...`, phải dùng cùng `-p <ten-project>` cho cả `down` và `up`. Phần này áp dụng stack mặc định ở cổng `8081`–`8084`; stack batch cần giữ đúng tên project và các Compose override của quy trình batch.

### 10.2. Tạo project demo mới, giữ nguyên dữ liệu cũ

Chọn cách này nếu muốn giữ database cũ để xem lại. Dừng stack đang giữ cổng mặc định trước; việc đổi tên project tách volume/network nhưng vẫn dùng các cổng `8081`–`8084`, `5432`, `5433`:

```powershell
docker compose stop
if ($LASTEXITCODE -ne 0) { throw 'Chua dung duoc stack cu.' }

$env:COMPOSE_PROJECT_NAME = 'flashsale-demo-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
Write-Host "Demo project: $env:COMPOSE_PROJECT_NAME"

docker compose up -d --build --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { throw 'Project demo moi chua healthy. Xem docker compose logs --tail=100.' }
docker compose ps
```

`COMPOSE_PROJECT_NAME` ở đây chỉ đặt cho phiên PowerShell hiện tại. Giữ phiên này để các lệnh `docker compose` còn lại trong hướng dẫn trỏ đúng demo mới. Nếu mở terminal khác, gán lại đúng tên đã in ra trước khi xem logs, truy vấn SQL hoặc dừng stack. Dữ liệu project cũ vẫn nằm trong volume cũ. Nếu còn stack khác chiếm cổng, xác định bằng `docker compose ls` rồi dừng đúng stack đó.

### 10.3. Kiểm tra dữ liệu ban đầu trước khi trình diễn

Sau khi chọn một cách ở trên và stack đã healthy, kiểm tra trực tiếp primary. Các lệnh này chỉ đọc dữ liệu:

```powershell
docker compose exec -T postgres-primary psql -U postgres -d inventorydb -c "SELECT id,name,available_stock FROM products ORDER BY id;"
if ($LASTEXITCODE -ne 0) { throw 'Khong doc duoc ton kho.' }

docker compose exec -T postgres-primary psql -U postgres -d paymentdb -c "SELECT customer_id,balance,currency FROM wallets ORDER BY customer_id;"
if ($LASTEXITCODE -ne 0) { throw 'Khong doc duoc so du vi.' }

docker compose exec -T postgres-primary psql -U postgres -d ordersdb -c "SELECT count(*) AS order_count FROM orders;"
if ($LASTEXITCODE -ne 0) { throw 'Khong doc duoc so don hang.' }

docker compose exec -T postgres-primary psql -U postgres -d paymentdb -c "SELECT count(*) AS ledger_count FROM payment_ledger;"
if ($LASTEXITCODE -ne 0) { throw 'Khong doc duoc ledger.' }
```

Kỳ vọng ngay sau seed, trước khi chạy bất kỳ script mua hàng nào:

| Dữ liệu | Giá trị ban đầu |
|---|---|
| SKU `11111111-1111-1111-1111-111111111111` | 100 sản phẩm |
| SKU `22222222-2222-2222-2222-222222222222` | 50 sản phẩm |
| SKU `33333333-3333-3333-3333-333333333333` | 0 sản phẩm |
| Ví Alice / Bob | Mỗi ví 100.000.000 VND |
| Ví poor | 0 VND |
| `order_count` / `ledger_count` | Đều bằng 0 |

Sau đó đăng nhập lại và demo các bước ở phần 6 hoặc test thiếu tiền/hết hàng ở phần 8. **Smoke test, load và thao tác đặt hàng đều thay đổi dữ liệu thật trong database demo.** Nếu muốn bắt đầu buổi trình diễn với đúng tồn kho 100 và ví 100.000.000, hãy kiểm tra bảng trên trước, chưa chạy smoke/load. Mật khẩu đăng nhập theo `.env` đã dùng khi seed; với cấu hình mẫu là `Demo@123` cho alice/bob/poor và `Admin@123` cho admin.

## Xử lý lỗi thường gặp trên PowerShell 5.1

| Hiện tượng | Cách xử lý |
|---|---|
| Không tìm thấy `pom.xml` hoặc Compose file | Vào đúng thư mục `FlashSale\FlashSale`, chạy lại hai lệnh `Test-Path` ở phần 1. |
| `running scripts is disabled` | Chạy `Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force` trong chính phiên đang dùng. Nếu máy có Group Policy, kiểm tra `Get-ExecutionPolicy -List` và làm theo chính sách của máy. |
| File tải về bị chặn dù đã `RemoteSigned` | Sau khi kiểm tra và tin cậy các script project, dùng `Get-ChildItem -LiteralPath .\scripts -Filter *.ps1 -File | Unblock-File`, rồi chạy lại. |
| `docker` không có server hoặc engine chưa sẵn sàng | Mở Docker Desktop, dùng Linux containers, chờ engine chạy rồi thử lại `docker version`. |
| Cổng đã bị sử dụng | Dùng `docker compose ls` và `Get-NetTCPConnection -LocalPort 8081,8082,8083,8084,5432,5433 -State Listen -ErrorAction SilentlyContinue` để xác định ứng dụng đang giữ cổng. Chỉ dừng đúng stack hoặc ứng dụng bạn đang dùng. |
| Container `unhealthy` | Đọc `docker compose ps --all` và `docker compose logs --tail=100`; không gọi API trước khi dependency healthy. |
| Đăng nhập `401` | Kiểm tra mật khẩu đã seed lần đầu. Đổi `.env` sau khi đã có volume không tự đổi tài khoản trong database. |
| `curl` xử lý tham số khác mong đợi | Trong Windows PowerShell 5.1, dùng `Invoke-RestMethod` như ví dụ. Nếu cần curl thật, gọi rõ `curl.exe`. |
| `&&`, `ForEach-Object -Parallel`, `-SkipHttpErrorCheck` không hợp lệ | Dùng bản script đã cập nhật trong project. Các ví dụ ở đây dùng từng lệnh riêng và kiểm tra `$LASTEXITCODE` cho Docker/Maven. |
| Chữ tiếng Việt trong JSON bị lỗi khi đọc bằng `Get-Content` | Chỉ định `-Encoding UTF8`; các artifact JSON/JSONL mới do script tạo dùng UTF-8. |
