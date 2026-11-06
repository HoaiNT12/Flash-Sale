# Kiểm thử Circuit Breaker bằng Apache JMeter

Bài test này kiểm tra Circuit Breaker **inventory bên trong order-service**. JMeter gửi đơn vào Order API, còn Saga worker gọi Inventory. File test tự đăng nhập, gây lỗi có kiểm soát, quan sát circuit mở, tắt lỗi và kiểm tra phục hồi.

**HTTP 202 của POST order chỉ xác nhận tiếp nhận đơn.** Tiêu chí chính là chuỗi sự kiện `CLOSED_TO_OPEN`, `OPEN_TO_HALF_OPEN`, `HALF_OPEN_TO_CLOSED`, trạng thái cuối `CLOSED` và các đơn đã kết thúc xử lý. Gọi trực tiếp catalog hoặc health không đi qua Circuit Breaker của Order.

## 1. Công cụ và file mẫu

Cần Java trên máy chạy JMeter, Docker Desktop Linux engine và Windows PowerShell 5.1. Bộ mẫu dùng **JMeter 5.6.3**, Java 21, không cần cài thêm JMeter Plugins. Tải bản **Binary ZIP** từ [Apache JMeter](https://jmeter.apache.org/download_jmeter.cgi) và giải nén nếu máy chưa có.

| File | Mục đích |
|---|---|
| [circuit-breaker.jmx](../tests/jmeter/circuit-breaker.jmx) | Test Plan mở bằng JMeter GUI hoặc chạy CLI |
| [circuit-breaker.groovy](../tests/jmeter/circuit-breaker.groovy) | Điều phối, theo dõi sự kiện, recovery và cleanup |
| [safe.properties](../tests/jmeter/safe.properties) | Lưu CSV/JTL không kèm request/response body hoặc headers chứa JWT |

Giữ `.jmx` và `.groovy` cùng thư mục. Các lệnh dưới đây chạy tại thư mục Maven:

```powershell
Set-Location 'C:\Users\Admin\OneDrive\Documents\java\FlashSale\FlashSale'
$PSVersionTable.PSVersion
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
java -version
```

Trong workspace hiện tại, bản JMeter dùng để kiểm chứng nằm ở `.local\tools\apache-jmeter-5.6.3`. Nếu cài ở nơi khác, thay giá trị `$jmeter` bằng đường dẫn tương ứng:

```powershell
$jmeter = Join-Path (Get-Location) '.local\tools\apache-jmeter-5.6.3\bin\jmeter.bat'
if (-not (Test-Path -LiteralPath $jmeter)) {
    throw 'Cần tải và giải nén JMeter, sau đó sửa biến $jmeter cho đúng đường dẫn.'
}
& $jmeter -v
if ($LASTEXITCODE -ne 0) { throw 'JMeter chưa chạy được. Kiểm tra Java và đường dẫn.' }
```

Nếu chưa chạy project lần nào, xem [hướng dẫn PowerShell 5.1](RUN_POWERSHELL_5_1.md).

## 2. Dựng stack demo riêng với dữ liệu mới

Không chạy cùng các script load/batch khác. Mỗi lượt test nên dùng một project name mới để có tồn kho và lịch sử circuit rõ ràng. Lượt này dùng SKU thứ hai có 50 sản phẩm, mỗi đơn mua một sản phẩm.

```powershell
$cbProject = 'flashsale-jmeter-' + (Get-Date -Format 'yyyyMMddHHmmss')
$cbCompose = @(
    'compose', '--env-file', '.env.example', '-p', $cbProject,
    '-f', 'docker-compose.yml',
    '-f', 'docker-compose.batch-test.yml',
    '-f', 'docker-compose.demo.yml'
)

docker @cbCompose up -d --build --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) {
    docker @cbCompose logs --tail=100
    throw 'Stack demo chưa healthy. Chưa chạy JMeter.'
}

docker @cbCompose ps
```

Thứ tự file Compose có ý nghĩa: demo override nằm cuối để bật `DEMO_ENABLED=true`. Các cổng host là Auth `18081`, Inventory `18082`, Payment `18083`, Order `18084`; PostgreSQL `15432/15433`. Đổi project name tạo volume mới nhưng **không tự đổi cổng host**. Nếu các cổng đang có stack test khác sử dụng, dừng đúng stack cũ trước. Giữ `$cbCompose` để xem logs và dừng đúng project sau test.

Nếu dùng stack mặc định đã chạy ở cổng `8081`–`8084`, bật demo bằng base + demo override và dùng các port mặc định của Test Plan. Không cần dựng đồng thời hai stack để làm cùng một bài test.

## 3. Kịch bản mà Test Plan thực hiện

| Giai đoạn | Thao tác | Điều cần kiểm tra |
|---|---|---|
| Chuẩn bị | Đăng nhập admin/alice một lần, tắt fault cũ, đọc trạng thái ban đầu | Đúng quyền, circuit bắt đầu ở CLOSED, SKU đủ hàng |
| Gây lỗi | Đặt Inventory `delayMs=1200`, `fail=true` | API nội bộ bị chậm rồi trả lỗi 503 |
| Tạo công việc | 8 threads × 4 vòng, mỗi POST có idempotency key riêng | 32 ý định mua; HTTP 202 chỉ là admission |
| Quan sát OPEN | Theo dõi state và events liên tục | Có CLOSED_TO_OPEN trong chính lượt chạy này |
| Phục hồi | Tắt fault, retry các request chưa rõ kết quả bằng cùng key/body | Không tạo đơn mới để thay cho request timeout/503 |
| Xác nhận phục hồi | Theo dõi HALF_OPEN/CLOSED và gửi 3 đơn kiểm tra sau phục hồi | Có OPEN_TO_HALF_OPEN và HALF_OPEN_TO_CLOSED |
| Kết thúc | Poll tất cả đơn, ghi kết quả và reset fault ở tearDown | Không còn đơn chưa kết thúc, có đơn probe COMPLETED |

Mặc định có 32 đơn tạo tải và 3 đơn probe. Không bắt buộc toàn bộ 32 đơn đầu đều COMPLETED: một số đơn có thể hết ngân sách retry và kết thúc REJECTED sau compensation. Các đơn COMPENSATING vẫn đang xử lý, chưa tính là đã kết thúc.

Observer đọc sự kiện mỗi khoảng 500 ms và tích lũy kết quả. Endpoint chỉ giữ buffer 100 sự kiện; chỉ đọc một lần ở cuối có thể mất sự kiện cũ. HALF_OPEN cũng có thể ngắn hơn chu kỳ quan sát, nên dùng sự kiện chuyển trạng thái để chứng minh thay vì yêu cầu phải chụp được state HALF_OPEN tại đúng thời điểm.

## 4. Chạy test bằng CLI từ PowerShell 5.1

Dùng CLI để tạo tải và xuất báo cáo, GUI để xem hoặc chỉnh Test Plan. Đây là cách chạy Apache khuyến nghị cho load test. [Hướng dẫn CLI của JMeter](https://jmeter.apache.org/usermanual/get-started.html#non_gui).

```powershell
$cbRun = 'cb-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
$cbOut = Join-Path (Get-Location) ('.local\jmeter-runs\' + $cbRun)
New-Item -ItemType Directory -Path $cbOut -Force | Out-Null

$jmArgs = @(
    '-n',
    '-t', '.\tests\jmeter\circuit-breaker.jmx',
    '-q', '.\tests\jmeter\safe.properties',
    '-l', (Join-Path $cbOut 'results.jtl'),
    '-j', (Join-Path $cbOut 'jmeter.log'),
    '-e', '-o', (Join-Path $cbOut 'html'),
    '-Jhost=127.0.0.1',
    '-JauthPort=18081',
    '-JinventoryPort=18082',
    '-JpaymentPort=18083',
    '-JorderPort=18084',
    '-Jthreads=8',
    '-Jloops=4',
    ('-JoutputDirectory=' + $cbOut)
)

& $jmeter @jmArgs
if ($LASTEXITCODE -ne 0) { throw 'JMeter chưa chạy xong hoặc không tạo được báo cáo. Xem jmeter.log.' }

$cbSummary = Get-Content -LiteralPath (Join-Path $cbOut 'summary.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$cbSummary | ConvertTo-Json -Depth 12
if ($cbSummary.status -ne 'PASS') {
    throw 'Test Circuit Breaker chưa đạt. Đọc summary.json, results.jtl và jmeter.log.'
}
```

Mỗi lượt dùng folder kết quả mới; không dùng tùy chọn xóa kết quả cũ. Folder `html` phải mới hoặc trống. JMeter có thể trả exit code 0 dù có sample/assertion lỗi, nên vẫn cần kiểm tra `summary.json` và các sample JTL.

Mở báo cáo HTML:

```powershell
Start-Process -FilePath (Join-Path $cbOut 'html\index.html')
```

Nếu POST checkout gặp 503/429 hoặc lỗi mạng, Test Plan ghi nhận phản hồi thực và xử lý lại cùng key/body. Tách lỗi admission này khỏi các lỗi 503 mà Order gặp khi gọi Inventory. Tổng số HTTP request có thể lớn hơn số ý định mua.

### Cách đọc kết quả

- `summary.json`: `status=PASS`, `initialState=CLOSED`, `finalState=CLOSED`, đủ ba sự kiện trong `orderedTransitionProof`, `pending=0`, `faultReset.httpStatus=200`.
- `requests`: số HTTP checkout thực đã gửi, bao gồm recovery cùng key. So với `acknowledgedOrders` để phân biệt số request với số đơn duy nhất.
- `orders[].endToEndMs`: thời gian từ lúc tạo ý định mua đến khi client quan sát thấy trạng thái terminal, có cả thời gian xếp hàng, retry và polling.
- `notPermittedObserved`: có quan sát lời gọi bị circuit đang OPEN từ chối hay không. Đây là bằng chứng riêng về fail-fast, không dùng tốc độ của POST order để suy ra.
- `results.jtl` và `html/index.html`: từng sampler và thống kê HTTP. Label `11 Checkout POST - native admission HTTP` đo admission; label `22 Recovery probe checkout POST - native admission HTTP` là các đơn probe. Các sampler `20` và `23` có chờ chủ động, không gộp chúng vào latency checkout.

Lượt kiểm chứng local ngày 31/10/2026 đã ghi nhận `CLOSED_TO_OPEN`, khoảng 10 giây sau là `OPEN_TO_HALF_OPEN`, rồi `HALF_OPEN_TO_CLOSED`. Cả 35 đơn COMPLETED, không còn pending và fault đã reset. Đây là kết quả của lượt cụ thể, không phải yêu cầu mọi lượt đều có 35 đơn thành công.

### Thay đổi tham số mà vẫn giữ test nhất quán

| Property | Mặc định | Ý nghĩa |
|---|---|---|
| `host`, `protocol` | `127.0.0.1`, `http` | Máy đích và giao thức |
| `authPort`, `inventoryPort`, `paymentPort`, `orderPort` | `8081`–`8084` | Dùng `18081`–`18084` khi chạy stack test riêng ở trên |
| `threads`, `loops` | `8`, `4` | Số đơn tạo tải bằng tích hai tham số |
| `productId` | SKU thứ hai, bắt đầu bằng `2222` | Cần tồn ít nhất `threads * loops + 3` |
| `delayMs`, `fail` | `1200`, `true` | Fault của Inventory |
| `timeoutSeconds` | `180` | Ngân sách chờ phục hồi và trạng thái đơn |
| `outputDirectory` | Folder riêng theo runId | Nơi ghi `summary.json` |
| `adminUsername`, `adminPassword` | `admin`, `Admin@123` | Tài khoản bật fault và đọc Actuator |
| `customerUsername`, `customerPassword` | `alice`, `Demo@123` | Chủ sở hữu các đơn trong test |

Giữ các biểu thức `__P(...)` trong Thread Group. Đổi `threads/loops` bằng tham số `-Jthreads=...`, `-Jloops=...` hoặc file properties để cả Thread Group và phần kiểm tra dùng cùng cấu hình. Test giới hạn tối đa 32 threads và 96 đơn tạo tải, nhưng vẫn phải có đủ tồn kho cho tổng đơn cộng 3 probe.

Nếu đã đổi mật khẩu seed, sao chép `safe.properties` thành file riêng trong `.local`, thêm các property credential vào file đó rồi thay đối số `-q` sang file riêng. Không đưa password lên dòng lệnh hoặc commit file credential. Với tham số không chứa secret, có thể thêm `-Jfail=false`, `-JdelayMs=1200` để thử tình huống phản hồi chậm nhưng không cố ý trả 503. Ngưỡng slow-call vẫn có thể mở circuit; cần dùng dữ liệu mới cho mỗi lượt.

## 5. Xem Test Plan trong JMeter GUI

Mở với cùng cấu hình cổng của stack demo:

```powershell
$jmGuiArgs = @(
    '-t', '.\tests\jmeter\circuit-breaker.jmx',
    '-q', '.\tests\jmeter\safe.properties',
    '-Jhost=127.0.0.1',
    '-JauthPort=18081', '-JinventoryPort=18082',
    '-JpaymentPort=18083', '-JorderPort=18084'
)
& $jmeter @jmGuiArgs
```

Trong cây Test Plan, xem lần lượt setUp Thread Group, nhóm gửi đơn, nhóm kiểm tra phục hồi và tearDown Thread Group. Các HTTP Request cho login và checkout dùng sampler gốc, có thể xem Method, URL, Header Manager và Body Data. Phần điều phối dùng Groovy đi kèm.

Để học từng request, có thể thêm View Results Tree trong một lượt nhỏ. Tắt listener này khi tạo tải. Không bật Functional Test Mode hoặc lưu request/response body cho file kết quả vì login trả JWT và request có Authorization header. [JMeter Best Practices](https://jmeter.apache.org/usermanual/best-practices.html#lean_mean).

## 6. Cấu hình Circuit Breaker đang được kiểm tra

Các thông số lấy từ `order-service/src/main/resources/application.yml`, áp dụng cho breaker Inventory:

| Thông số | Giá trị |
|---|---:|
| Sliding window | 20 lời gọi |
| Số mẫu tối thiểu | 10 |
| Tỷ lệ lỗi để mở circuit | 50% |
| Ngưỡng lời gọi chậm | 800 ms |
| Tỷ lệ lời gọi chậm để mở circuit | 50% |
| Thời gian OPEN | 10 giây |
| Số lời gọi thăm dò HALF_OPEN | 3 |
| Chờ tối đa ở HALF_OPEN | 5 giây |
| Bulkhead Inventory | 8 lời gọi đồng thời, không chờ slot |
| Timeout HTTP downstream | 2 giây |

Không suy ra rằng cứ gửi 10 POST là circuit sẽ mở. Một POST tạo một Saga, worker có thể thử lại nhiều lần; Inventory breaker còn bao quanh reserve, confirm và release. Bulkhead đầy cũng có thể từ chối trước khi lời gọi đi vào Circuit Breaker.

Các lỗi nghiệp vụ như OUT_OF_STOCK được bỏ qua trong thống kê lỗi của breaker. Vì vậy, bắn nhiều đơn vào SKU đã hết hàng không phải cách kiểm chứng OPEN do lỗi hạ tầng.

## 7. Tự quan sát bằng API trong terminal khác

Đăng nhập bằng admin, không dùng token alice cho Actuator:

```powershell
$adminLogin = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18081/api/auth/login' -ContentType 'application/json' -Body '{"username":"admin","password":"Admin@123"}' -TimeoutSec 10
$adminHeaders = @{ Authorization = "Bearer $($adminLogin.accessToken)" }

$states = Invoke-RestMethod -Uri 'http://127.0.0.1:18084/actuator/circuitbreakers' -Headers $adminHeaders -TimeoutSec 10
$states.circuitBreakers.inventory | Format-List

$events = Invoke-RestMethod -Uri 'http://127.0.0.1:18084/actuator/circuitbreakerevents/inventory/STATE_TRANSITION' -Headers $adminHeaders -TimeoutSec 10
$events.circuitBreakerEvents | Select-Object creationTime, stateTransition
```

`failureRate` và `slowCallRate` là chuỗi phần trăm; khi chưa đủ mẫu có thể là `-1.0%`. Sau khi chuyển trạng thái, thống kê có thể reset. Xem snapshot lúc OPEN hoặc lịch sử events, không yêu cầu failedCalls vẫn tăng sau khi đã CLOSED.

Nếu muốn kiểm chứng từ chối nhanh, xem sự kiện `NOT_PERMITTED` hoặc `notPermittedCalls` ở breaker Inventory. Không dùng riêng latency của POST `/api/orders` để kết luận lời gọi downstream đã fail-fast. API Actuator là nguồn quan sát trạng thái và metrics của Resilience4j. [Resilience4j Spring Boot](https://resilience4j.readme.io/docs/getting-started-3).

## 8. Dừng test, reset fault và giữ dữ liệu

Test Plan có tearDown reset fault. Nếu đóng JMeter cưỡng bức, mất điện hoặc kill process, cleanup có thể không chạy. Dùng terminal để reset và xác nhận:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18082/demo/faults' -Headers $adminHeaders -ContentType 'application/json' -Body '{"delayMs":0,"fail":false}' -TimeoutSec 10
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:18082/demo/faults' -Headers $adminHeaders -TimeoutSec 10
```

Kỳ vọng `delayMs=0`, `fail=false`. Nếu admin token đã hết hạn, đăng nhập lại trước khi reset.

```powershell
docker @cbCompose logs --tail=100 order-service inventory-service
docker @cbCompose stop
```

Giữ volumes để xem order history và ledger. Không xóa dữ liệu hoặc sửa trạng thái đơn thủ công để làm kết quả test thành công.

## 9. Chẩn đoán kết quả chưa đạt

| Hiện tượng | Cách kiểm tra |
|---|---|
| `/demo/faults` trả 404 | Demo override chưa nằm cuối hoặc container chưa bật DEMO_ENABLED. |
| 401/403 khi bật fault hay đọc Actuator | Cần JWT admin, đúng issuer/audience và token còn hạn. |
| Không quan sát được OPEN | Xác nhận bắn vào Order API, fault đã bật, đủ lời gọi downstream thực và không chỉ có lỗi nghiệp vụ. |
| HALF_OPEN khó nhìn thấy | Xem event chuyển trạng thái; snapshot có thể bỏ lỡ trạng thái ngắn. |
| Circuit OPEN trở lại sau reset | Còn lời gọi đang mang fault cũ hoặc downstream vẫn lỗi/chậm. Chờ thêm một chu kỳ và xem logs. |
| Đơn ở COMPENSATING | Chưa hoàn tất refund/release; reset fault và chờ, không coi là REJECTED terminal. |
| OUT_OF_STOCK hoặc sale đóng từ đầu | Dùng project demo mới có dữ liệu seed; chạy lại không tự bổ sung tồn kho. |
| GUI mở được nhưng chạy báo thiếu Groovy | Giữ file `.groovy` cùng `.jmx`, chạy từ project root và kiểm tra đường dẫn script. |
| Docker trả API 500 hoặc không đọc được containers | Kiểm tra Docker Desktop Linux engine đã sẵn sàng bằng `docker version`, sau đó chạy lại lệnh dừng/khởi động đúng project. Không coi lệnh stop bị lỗi là container đã dừng. |
| Exit code 0 nhưng summary FAIL | Có thể có assertion/sample lỗi; đọc summary và JTL, không dùng exit code làm tiêu chí duy nhất. |

Bài test chứng minh chuyển trạng thái và phục hồi của Circuit Breaker trong môi trường local có lỗi mô phỏng. Dashboard JMeter chứa cả sampler điều phối/polling có thời gian chờ dài; đọc số liệu theo từng label HTTP, không lấy p95 gộp làm latency checkout hoặc SLA production. Kiểm tra đầy đủ tiền/tồn/ledger sau tải là bài riêng trong [BATCH_ORDER_TEST.md](BATCH_ORDER_TEST.md).
