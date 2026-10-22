# Kiểm thử tính đúng đắn, quá tải và khôi phục

## Build và tests tự động

```powershell
.\mvnw.cmd -B -ntp verify
```

Tests sử dụng JUnit 5, AssertJ, MockMvc, ArchUnit và Testcontainers PostgreSQL 17. Không dùng H2 để chứng minh row-lock/atomic SQL của PostgreSQL. Khi Docker không có engine hợp lệ, các integration test đánh dấu `disabledWithoutDocker=true` sẽ skip; phải đọc phần Skipped trong Surefire, không coi skip là đã kiểm thử database.

| Nhóm | Trường hợp quan trọng |
|---|---|
| Common JWT/security | Chữ ký thật qua JWKS loopback; wrong/missing audience, wrong issuer, expired, tampered token; shop/admin không gọi internal; internal không gọi shop |
| Auth | Credential đúng/sai/disabled; scope isolation; BCrypt input UTF8; JWT claims/key survives restart; rate limit; HTTP 401 contract |
| Inventory | 160 khách tranh 100 hàng; 64 retry cùng order; release đồng thời chỉ hoàn một lần; reserve/release race; tombstone; sale expiry; rollback lỗi storage |
| Payment | 40 concurrent duplicate charge/refund; nhiều đơn tranh số dư; insufficient funds rollback; void/charge race; ledger; HTTP lỗi và domain purity |
| Order | Quote server; deadline; retry deferred; charge không rõ kết quả phải void; compensation không bỏ cuộc; breaker không tính business error; OPEN/fail-fast/HALF_OPEN/recovery; fenced lease reclaim; backlog/idempotency |
| Architecture | Domain/application không import Spring/JDBC/common HTTP/adapters/infrastructure |

## Smoke và cạnh tranh tồn kho

Khởi động một stack phát triển riêng, không có buyer khác. Script có tác động thật tới dữ liệu demo, không chạy nhiều script đồng thời.

```powershell
docker compose up -d --build --wait --wait-timeout 240
.\scripts\smoke.ps1
.\scripts\load.ps1 -Requests 200 -Concurrency 32
```

Smoke tạo một đơn alice thành công và retry cùng key, kiểm tra balance debit đúng amount, bob không đọc đơn alice, customer không được void payment, input sai trả 400, poor thiếu tiền được compensation. Cuối script chờ replica phản ánh tồn ban đầu trừ một để làm baseline ổn định cho load tiếp theo.

Load gửi POST song song bằng PowerShell 7, mỗi ý định mua một key; poll toàn bộ đơn tới terminal, cộng amount COMPLETED bằng decimal, đối chiếu giảm ví và kiểm tra tổng số đơn thành công không vượt tồn baseline. Replica có tối đa 15 giây để bắt kịp stock sau khi tất cả đơn đã xong. Các lỗi admission không kỳ vọng làm test fail thay vì bị bỏ qua. Đây là phép kiểm tra dữ liệu và submission time; không phải báo cáo benchmark p95/p99 production.

Sau smoke trên DB mới, SKU đầu còn 99 hàng. Với 200 buyer quantity=1 và ví đủ tiền, kỳ vọng 99 COMPLETED; các đơn còn lại REJECTED/OUT_OF_STOCK nếu toàn bộ được admission. Circuit không mở do OUT_OF_STOCK vì đó là lỗi nghiệp vụ. Chạy lại trên cùng volume dùng số tồn hiện tại, không mặc định seed lại 100.

## Circuit Breaker quá tải

```powershell
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build --wait --wait-timeout 240
.\scripts\overload.ps1
```

Thông số thật nằm trong `order-service/src/main/resources/application.yml`:

| Thông số | Giá trị | Tác dụng |
|---|---:|---|
| Sliding window | COUNT_BASED, 20 | Thống kê các lời gọi gần nhất |
| Minimum calls | 10 (auth: 5) | Đủ mẫu trước khi tính tỷ lệ |
| Failure threshold | 50% | 5xx/network errors mở circuit |
| Slow threshold | 800 ms, 50% | Mở circuit khi nhiều call chậm |
| Open wait | 10 giây | Chờ downstream giảm tải |
| Half-open permits | 3 | Số call thăm dò phục hồi |
| Half-open max wait | 5 giây | Tránh trạng thái thăm dò kéo dài |
| Bulkhead | 8 call/downstream, wait 0 | Từ chối nhanh khi hết chỗ |
| HTTP | connect 1 giây, request/read 2 giây | Giới hạn chờ mỗi call |
| Saga | 16 worker, queue 1.000 | Giới hạn workload và bộ nhớ |

Overload script dùng 503 + delay 1.200 ms trên inventory, tạo 32 đơn SKU thứ hai, đọc actuator đến OPEN, reset fault trong `finally`, chờ tất cả đơn terminal. Bước phục hồi cần chờ OPEN timeout rồi có calls thăm dò HALF_OPEN; xem event log để kiểm tra CLOSED. Có đơn có thể REJECTED vì hết retry budget, nhưng phải được refund/release trước terminal. CI đã có bước chạy kịch bản này sau smoke/load.

Để thử **chậm nhưng thành công**, đặt `fail=false,delayMs=1200`: slow-call threshold vẫn có thể mở circuit. Để thử **timeout và late request**, đặt `delayMs=4000,fail=false`; HTTP timeout 2 giây không ngăn service nhận tác vụ đến muộn. Sau đó reset fault và đối chiếu tombstone/ledger. API fault chỉ tồn tại khi DEMO_ENABLED=true và cần admin JWT:

```powershell
$auth = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/auth/login -ContentType application/json -Body '{"username":"admin","password":"Admin@123"}'
$headers = @{Authorization="Bearer $($auth.accessToken)"}
Invoke-RestMethod -Method Post -Uri http://localhost:8082/demo/faults -Headers $headers -ContentType application/json -Body '{"delayMs":4000,"fail":false}'
Invoke-RestMethod -Uri http://localhost:8084/actuator/circuitbreakerevents -Headers $headers
# Reset sau test:
Invoke-RestMethod -Method Post -Uri http://localhost:8082/demo/faults -Headers $headers -ContentType application/json -Body '{"delayMs":0,"fail":false}'
```

Fault state giữ trong RAM từng instance, không phải cấu hình cluster. Demo không bật mặc định. Nếu muốn thử payment unavailable lâu, bật faults ở port 8083, quan sát COMPENSATING; reset và đợi trạng thái cuối thay vì sửa thẳng orders.

## Crash recovery

1. Gửi các checkout rồi ghi lại order IDs và idempotency keys.
2. Trong khi đang xử lý, chạy `docker compose kill -s SIGKILL order-service` và `docker compose up -d order-service`.
3. Chờ lease 30 giây hết hạn và poll các đơn. Nếu HTTP charge/reserve đã thành công trước crash, retry không tạo thêm debit hay stock decrement.
4. Retry POST cũ phải trả cùng order ID. SQL lịch sử có thể có retry, nhưng ledger debit/refund không trùng.

Restart auth bằng `docker compose restart auth-service` rồi dùng token còn hạn để gọi catalog/order. Private/public key volume không được thay đổi khi restart. Restart primary/replica giữ volume; service tự kết nối lại, Saga vẫn còn trong ordersdb.

## SQL audit sau khi hệ thống đã ổn định

`scripts/check-consistency.sql` có ba section ghi rõ database; chạy section đúng DB, không chạy toàn bộ file vào một DB. Kỳ vọng các invariant query trả **0 hàng**. Script giả định seed ban đầu và không có topup/stock replenishment thủ công.

Ví dụ mở phiên SQL:

```powershell
docker compose exec postgres-primary psql -U postgres -d inventorydb
docker compose exec postgres-primary psql -U postgres -d paymentdb
docker compose exec postgres-primary psql -U postgres -d ordersdb
```

Ngoài tổng stock/balance, đối chiếu liên context cho các terminal order: COMPLETED phải có CONFIRMED/CHARGED cùng orderId và đúng amount; REJECTED phải CANCELLED/VOIDED. Audit ở trạng thái đang chạy có thể thấy RESERVED với order ACCEPTED hoặc payment CHARGED đang chờ confirm — đó là state trung gian hợp lệ. Không khẳng định transaction ACID xuyên service.

## Replica và backlog

Tạm dừng replica rồi đọc catalog có thể lỗi/stale; command reserve/payment/order vẫn chỉ dùng primary. Khởi động lại replica và xem `pg_stat_replication`/WAL lag. Default async không có yêu cầu commit chờ replica; mất primary có thể mất những WAL chưa replicate, được giải thích trong OPERATIONS.

Để thử admission nhanh: đặt `SAGA_MAX_PENDING=10` cho order-service và gây downstream unavailable; POST vượt backlog trả 429. Giữ key khi retry sau recovery. Không tăng unbounded virtual threads hoặc DB pool để che hiện tượng queue đầy.

CI giả định Docker-enabled GitHub runner. Unit/HTTP tests và parser checks có thể chạy tại máy này; kết quả container và các script cần xác nhận trong môi trường có Docker, được ghi riêng trong VALIDATION.
