# Sàn thương mại điện tử — backend Flash Sale

Project Java **21**, Spring Boot **3.5.16**, Maven Wrapper **3.9.11**, Lombok và Resilience4j **2.3.0**. Bốn microservice có thể chạy riêng và giao tiếp HTTP có xác thực. Backend gồm đăng nhập, danh sách sản phẩm/flash sale, đặt hàng bất đồng bộ, giữ hàng, ví thanh toán, hoàn tiền và truy vấn trạng thái đơn.

Thanh toán sử dụng **ví mô phỏng có ledger PostgreSQL**, giúp kiểm thử chính xác debit/refund. Chưa tích hợp ngân hàng, thẻ hay cổng thanh toán thực. Các tính năng storefront, giỏ hàng nhiều SKU, vận chuyển và đăng ký tài khoản nằm ngoài phạm vi bản này.

## Đáp ứng yêu cầu

| Yêu cầu | Triển khai |
|---|---|
| Java Spring Boot, Maven, Lombok | Parent Maven + 5 module; 4 executable Spring Boot JAR, 1 thư viện kỹ thuật |
| Service bảo mật bằng Spring Security | `auth-service` xác minh BCrypt, phát JWT RS256 và JWKS công khai; từng service kiểm tra chữ ký, issuer, audience, thời hạn và scope |
| Ít nhất 2 microservice giao tiếp | `order-service` gọi auth, inventory và payment; không truy cập DB của service khác |
| Architectural pattern | **Saga orchestration** với state machine/work queue lưu bền PostgreSQL, retry và compensation |
| Circuit Breaker khi gọi API | Resilience4j riêng cho inventory/payment/auth, kết hợp bulkhead và HTTP timeout |
| Clean Architecture + DDD | Domain/aggregate/value object → application/ports → adapters/infrastructure → configuration; không chia sẻ domain entity giữa service |
| Bất đồng bộ | HTTP `202` sau khi lưu đơn; Saga worker dùng **Java 21 virtual threads**, semaphore và PostgreSQL `SKIP LOCKED` |
| Tồn kho và thanh toán | SQL cập nhật có điều kiện, transaction, row lock, unique key, idempotency và cancellation tombstone |
| Database có replica và dữ liệu mẫu | PostgreSQL 17 primary + physical streaming replica; 4 database/owner riêng, Flyway và seed |
| Tái hiện quá tải | Demo override + fault injection được bảo vệ bằng scope admin, script quan sát OPEN và khôi phục |

## Chạy nhanh trên Windows

Mở terminal tại thư mục chứa README này (`...\FlashSale\FlashSale`). Cần Docker Desktop với Linux engine đang chạy. Chạy toàn bộ bằng Docker không cần cài Maven hay JDK trên host; build/test trên host cần JDK 21.

```powershell
Copy-Item .env.example .env
.\mvnw.cmd -B -ntp verify
docker compose up -d --build --wait --wait-timeout 240
docker compose ps
.\scripts\smoke.ps1
```

Linux/macOS dùng `bash mvnw -B -ntp verify`; các script `.ps1` cần PowerShell 7 (`pwsh`). Không cần cài Maven riêng. Lần đầu tải dependencies và Docker images sẽ lâu hơn. Giữ `.env` ngoài Git.

| Thành phần | Địa chỉ host | Database |
|---|---|---|
| Auth | `http://localhost:8081` | `authdb`, owner `auth` |
| Inventory/catalog | `http://localhost:8082` | `inventorydb`, owner `inventory` |
| Payment/wallet | `http://localhost:8083` | `paymentdb`, owner `payment` |
| Order/Saga | `http://localhost:8084` | `ordersdb`, owner `orders` |
| PostgreSQL primary | `localhost:5432` | Write và strong-consistency reads |
| PostgreSQL replica | `localhost:5433` | Read-only catalog trong Compose |

Các port chỉ bind `127.0.0.1`. Mật khẩu DB phát triển nằm trong `.env.example`; `.env` chỉ seed credentials lúc khởi tạo. Đổi biến môi trường sau đó không tự đổi password/hash đã lưu; xem [vận hành](docs/OPERATIONS.md).

## Tài khoản và hàng mẫu

| Tài khoản | Mật khẩu mặc định | Scope | Ví VND |
|---|---|---|---:|
| `alice` | `Demo@123` | shop | 100.000.000 |
| `bob` | `Demo@123` | shop | 100.000.000 |
| `poor` | `Demo@123` | shop | 0 |
| `admin` | `Admin@123` | shop admin | Không seed ví |

Machine client `order-service` / `order_dev_secret` nhận scope `internal`, tách khỏi đăng nhập người dùng. JWT có hạn 15 phút. Auth lưu RSA private key trong volume `auth-keys`; JWKS chỉ chứa public key.

| Product ID | Tên | Giá VND | Tồn ban đầu |
|---|---|---:|---:|
| `11111111-1111-1111-1111-111111111111` | Flash Sale Keyboard | 99.000 | 100 |
| `22222222-2222-2222-2222-222222222222` | Flash Sale Headphones | 199.000 | 50 |
| `33333333-3333-3333-3333-333333333333` | Sold Out Mouse | 59.000 | 0 |

Sale mẫu mở từ 365 ngày trước đến 365 ngày sau lần migration đầu tiên. Client chỉ gửi product ID và quantity (1..5); service tồn kho quyết định giá tại lúc giữ hàng. Tiền dùng `BigDecimal` và `NUMERIC`, chỉ hỗ trợ VND với giá dương.

## Mua thử

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/auth/login -ContentType application/json -Body '{"username":"alice","password":"Demo@123"}'
$headers = @{ Authorization = "Bearer $($login.accessToken)"; 'Idempotency-Key' = "buy-$([guid]::NewGuid())" }
$order = Invoke-RestMethod -Method Post -Uri http://localhost:8084/api/orders -Headers $headers -ContentType application/json -Body '{"productId":"11111111-1111-1111-1111-111111111111","quantity":1}'
Invoke-RestMethod -Uri "http://localhost:8084/api/orders/$($order.id)" -Headers $headers
```

`202 ACCEPTED` nghĩa là đã lưu yêu cầu, **chưa phải thanh toán thành công**. Poll GET đến `COMPLETED` hoặc `REJECTED`. Giữ nguyên `Idempotency-Key` khi retry cùng yêu cầu; đổi key chỉ cho một lần mua mới. Cùng khách hàng và cùng key nhưng payload khác trả `409`. Không tái sử dụng key của đơn đã bị từ chối để tạo đơn mới.

Saga: `RESERVE → PAY → CONFIRM → COMPLETED`; lỗi nghiệp vụ/hết ngân sách retry: `VOID_PAYMENT → RELEASE → REJECTED`. Compensation tiếp tục retry khi downstream chưa khôi phục, nên đơn có thể ở `COMPENSATING` lâu hơn deadline mua hàng. Chi tiết về timeout, restart và request đến muộn tại [kiến trúc](docs/ARCHITECTURE.md).

## Tạo tải và mô phỏng Circuit Breaker

```powershell
# Stack đang chạy, không có người mua khác hoặc faults đang bật:
.\scripts\load.ps1 -Requests 200 -Concurrency 32

# Chuyển sang cấu hình demo:
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build --wait --wait-timeout 240
.\scripts\overload.ps1
```

Script overload bật lỗi 503 và độ trễ 1.200 ms ở inventory, tạo đơn, chờ quan sát circuit `OPEN`, reset fault trong `finally`, rồi chờ đơn hoàn thành hoặc được hoàn tác. Chạy lại có thể tiêu thụ hàng mẫu; [TESTING.md](docs/TESTING.md) mô tả test thiếu hàng, thiếu tiền, timeout, recovery, replica và kiểm tra SQL.

## Tài liệu và kiểm chứng

- [ARCHITECTURE.md](docs/ARCHITECTURE.md): bounded contexts, Clean Architecture, state machine và các invariant.
- [API.md](docs/API.md): endpoint, request/response, scope và lỗi.
- [TESTING.md](docs/TESTING.md): kiểm thử cạnh tranh, tải, fault injection, restart và dữ liệu.
- [OPERATIONS.md](docs/OPERATIONS.md): primary/replica, synchronous replication, cấu hình và giới hạn.
- [VALIDATION.md](docs/VALIDATION.md): kết quả kiểm chứng trong môi trường tạo project.
- [BATCH_ORDER_TEST.md](docs/BATCH_ORDER_TEST.md): input/output thực tế và lệnh gửi hàng loạt order API, đối chiếu primary/replica và ledger.
- [inventory-service/README.md](inventory-service/README.md) và [payment-service/README.md](payment-service/README.md): chi tiết transaction từng aggregate.

Integration test dùng PostgreSQL thật qua Testcontainers, tự skip khi không có Docker. CI tại `.github/workflows/ci.yml` chạy trên Docker-enabled runner, build toàn bộ, integration test, Compose và smoke/load. Không dùng kết quả unit test để khẳng định throughput hay khả năng failover production.
