# Database replica, cấu hình và vận hành

## Vì sao PostgreSQL

Tồn kho/ví cần cập nhật nguyên tử, row locks, unique constraints, transaction và số tiền NUMERIC chính xác. PostgreSQL đáp ứng các nhu cầu này và có streaming replication. Redis/cache không được dùng làm nguồn quyết định số dư hay tồn kho trong bản này, tránh thêm một nơi phải đồng bộ correctness.

Compose tạo PostgreSQL 17 primary và một replica read-only bằng `pg_basebackup`, `standby.signal`, replication slot `flashsale_replica` và streaming WAL. Bốn database có role riêng, revoke PUBLIC access tới database nghiệp vụ. Các service chạy non-root; auth keys và DB data dùng named volumes. Đây là topology phát triển trên một host, không tạo HA giữa nhiều máy.

## Kiểm tra replication

```powershell
docker compose exec -T postgres-primary psql -U postgres -d postgres -c "SELECT application_name,state,sync_state,write_lag,flush_lag,replay_lag FROM pg_stat_replication;"
docker compose exec -T postgres-replica psql -U postgres -d postgres -c "SELECT pg_is_in_recovery(),pg_last_wal_receive_lsn(),pg_last_wal_replay_lsn();"
docker compose exec -T postgres-replica psql -U postgres -d inventorydb -c "SELECT id,available_stock FROM products ORDER BY id;"
```

Kỳ vọng application_name `flashsale_replica`, state `streaming`, sync_state `async` mặc định và pg_is_in_recovery=true. Không route POST/checkout/wallet-read/order-status tới replica. Catalog đọc replica có thể trả tồn cũ và sale sản phẩm vừa đổi; reserve luôn kiểm tra primary lại. Chưa có tự động fallback catalog khi replica lỗi; có thể bỏ `APP_CATALOG_JDBC_URL` để chạy catalog trên primary.

Replication slot có `max_slot_wal_keep_size=1GB` để giới hạn giữ WAL; nếu replica ngừng quá lâu có thể vượt giới hạn và cần tạo lại replica từ primary. Entry point không tự xóa dữ liệu. Nếu base backup bị gián đoạn để lại PG_VERSION nhưng chưa standby.signal, replica từ chối startup, tránh mở bản sao chưa hoàn chỉnh thành primary.

Default streaming là **asynchronous**: commit nhanh hơn nhưng WAL chưa truyền có thể mất nếu primary hỏng và promote replica. `fsync` và `synchronous_commit` trên primary vẫn bật mặc định; điều đó không đồng nghĩa replica đã nhận WAL. Tính đúng đắn transaction trên primary không xóa rủi ro mất dữ liệu khi failover async.

## Thử synchronous replication

Chỉ bật sau khi replica đã streaming. SQL `ALTER SYSTEM` chạy ngoài transaction; tài khoản quản trị postgres thực hiện. Từ PowerShell, pipe file tới psql:

```powershell
Get-Content .\infra\postgres\enable-synchronous-replication.sql | docker compose exec -T postgres-primary psql -U postgres -d postgres -v ON_ERROR_STOP=1
docker compose exec -T postgres-primary psql -U postgres -d postgres -c "SHOW synchronous_standby_names; SHOW synchronous_commit;"
# Tắt chờ replica để về cấu hình demo mặc định:
Get-Content .\infra\postgres\disable-synchronous-replication.sql | docker compose exec -T postgres-primary psql -U postgres -d postgres -v ON_ERROR_STOP=1
```

`FIRST 1 (flashsale_replica)` và synchronous_commit=on chờ replica flush WAL bền trước khi trả commit. Khi replica mất kết nối, commit có thể chờ; HTTP/driver timeout không chứng minh transaction chưa commit. Retry cùng orderId/key và reconciliation vẫn cần. Chế độ on đảm bảo WAL flush, không đảm bảo query replica đã replay thấy hàng vừa commit; remote_apply có semantics khác. Compose chưa có automatic failover, leader election, fencing primary hay connection routing sau promote. Không tự promote replica rồi tiếp tục để primary cũ nhận write.

## Cấu hình chính

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| SPRING_DATASOURCE_URL/USERNAME/PASSWORD | Theo module | DB primary của mỗi service |
| APP_CATALOG_JDBC_URL/USERNAME/PASSWORD | URL trống khi chạy JAR | Catalog datasource; Compose trỏ replica |
| APP_SECURITY_ISSUER | http://localhost:8081 | Claim issuer cố định trong mọi token/decoder |
| APP_SECURITY_JWK_SET_URI | localhost:8081 JWKS | Compose dùng hostname auth-service nội bộ |
| APP_SECURITY_AUDIENCE | flashsale-api | Audience bắt buộc |
| AUTH_KEY_DIRECTORY | .local/auth-keys | Compose /keys, persistent volume |
| AUTH_DEMO_PASSWORD / AUTH_ADMIN_PASSWORD | Trong .env.example | Password seed lần đầu |
| AUTH_ORDER_SERVICE_SECRET | order_dev_secret | Secret seed client auth |
| SERVICE_CLIENT_ID / SERVICE_CLIENT_SECRET | order-service / order_dev_secret | Credentials order gọi auth; Compose truyền secret từ AUTH_ORDER_SERVICE_SECRET |
| AUTH_BASE_URL / INVENTORY_BASE_URL / PAYMENT_BASE_URL | localhost theo port | Outbound service URL |
| SAGA_CONCURRENCY / SAGA_MAX_PENDING | 16 / 1000 | Worker permit và durable backlog cap |
| DEMO_ENABLED | false | Bật endpoint admin fault injection |

Thay đổi `JWT_ISSUER`/`JWT_AUDIENCE` trong `.env` Compose cập nhật auth và resource services cùng lúc. Issuer URL là claim identity, JWKS URL là địa chỉ truy cập thực; chúng có thể khác giữa host client và Docker network. Không dùng resource service để phát token; private key chỉ ở auth.

Khi chạy JAR local, datasource defaults đã khớp port primary 5432 và dev credentials. Nếu đã tùy chỉnh `.env`, truyền lại SPRING_DATASOURCE_PASSWORD tương ứng cho từng JAR. Có thể chạy hạ tầng riêng:

```powershell
docker compose up -d --wait postgres-primary postgres-replica
.\mvnw.cmd -B -ntp package -DskipTests
java -jar .\auth-service\target\auth-service-1.0.0-SNAPSHOT.jar
# Terminal riêng cho inventory, payment, order bằng JAR tương ứng.
```

Mặc định catalog JAR local dùng primary; để thử replica trên host đặt `$env:APP_CATALOG_JDBC_URL='jdbc:postgresql://localhost:5433/inventorydb'` trước khi chạy inventory. Không chạy JAR host trùng port với service Docker đang chạy.

## Quan sát và recovery

```powershell
docker compose logs -f --tail=100 order-service inventory-service payment-service
docker compose exec -T postgres-primary psql -U postgres -d ordersdb -c "SELECT status,step,count(*) FROM orders GROUP BY status,step;"
docker compose exec -T postgres-primary psql -U postgres -d ordersdb -c "SELECT id,step,attempts,lease_until,next_attempt,failure_code FROM orders WHERE step <> 'FINISHED' ORDER BY created_at LIMIT 30;"
```

Actuator health summary public; metrics/circuit events trên order cần admin bearer token. Circuit mở không cố ý làm health DOWN, tránh restart service khi downstream đang quá tải. Theo dõi queue depth, tuổi đơn lâu nhất, COMPENSATING kéo dài, timeout, pool saturation, Circuit OPEN, WAL lag và dung lượng slot/volume. `order_history` giúp giải thích retry/compensation; ledger cho biết debit/refund đã commit.

Nếu Order chết, khởi động lại coordinator rồi để expired lease recovery. Nếu downstream chưa online, giữ đơn COMPENSATING và retry có backoff. Không sửa order thành REJECTED/COMPLETED thủ công để bỏ qua các bước reconciliation. Không xóa tombstones, payment ledger hoặc idempotency records trước khi xác định không còn late requests.

## Dữ liệu và credentials

`docker compose down` dừng/xóa container và giữ volumes. Chỉ primary lần init đầu chạy shell tạo role/database; Flyway migrations và seed không chạy lại trên mỗi restart. Auth initializer dùng ON CONFLICT DO NOTHING nên password/secret đã có không được đổi chỉ bằng sửa `.env`. Password DB phải đổi bằng ALTER ROLE và cấu hình client đồng thời; identity password/client secret cần cập nhật BCrypt hash trong authdb bằng quy trình quản trị.

Không phát lệnh reset dữ liệu trong quy trình chạy bình thường. Nếu muốn demo sạch, sao lưu trước và tạo một Compose project name mới (đổi host ports hoặc dừng stack cũ); project mới có named volumes riêng. Xóa volumes sẽ mất đơn, hàng, ví, ledger và signing key; token cũ cũng không còn phù hợp với key mới.

## Giới hạn trước production

Bản này cung cấp backend mẫu có invariant và test, chưa là sàn hoàn chỉnh. Chưa có cổng thanh toán thật, webhook/reconciliation bên ngoài, refresh/revocation, distributed rate limiting, catalog pagination/search, multi-SKU/cart, giao vận hoặc tự động failover DB. Wallet hiện không có topup API. TLS và secret management cần cấu hình cho môi trường triển khai; credentials mẫu/HTTP chỉ dùng local.

Hot SKU vẫn serialize trên một product row, ví cùng khách serialize debit và advisory admission serialize tạo đơn; virtual threads giảm chi phí chờ, không làm tăng công suất DB vô hạn. Nâng tải cần đo trên hạ tầng thật và điều chỉnh budget queue/DB/bulkhead, giữ tính đúng đắn trước khi phân vùng/shard. Không có tuyên bố throughput hoặc p99 chưa đo.

Nếu thay ví bằng PSP, transaction nội bộ không bao trùm tiền bên PSP. Adapter phải có provider idempotency key, trạng thái UNKNOWN, truy vấn/reconciliation, webhook xác thực và refund idempotent. Không giữ DB transaction qua HTTP, không coi timeout là decline, không đánh dấu refund thành công nếu PSP chưa xác nhận.

## Tài liệu kỹ thuật tham chiếu

- [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html): Java/build compatibility.
- [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html): chữ ký, issuer, audience và scope validation.
- [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker): sliding window, failure/slow-call threshold, half-open và giới hạn concurrency qua bulkhead.
- [PostgreSQL 17 streaming/synchronous replication](https://www.postgresql.org/docs/17/warm-standby.html): async mặc định, WAL/standby và synchronous commit.
- [pgJDBC connection properties](https://jdbc.postgresql.org/documentation/use/): connect/socket/cancel timeouts và SQL options.
