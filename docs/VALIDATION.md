# Kết quả kiểm chứng

Ngày kiểm tra: **14/10/2026**, múi giờ Asia/Bangkok. Workspace được chuyển từ bộ khung ban đầu thành Maven multi-module. Lệnh cuối:

```powershell
.\mvnw.cmd -B -ntp verify
```

**BUILD SUCCESS** cho parent, common và cả 4 service. Tạo đủ executable Spring Boot JAR cho auth, inventory, payment, order. JDK host 21.0.11; Maven Wrapper 3.9.11. Thời gian lượt verify cuối khoảng 76 giây; đây là thời gian build/test, không phải latency ứng dụng.

| Module | Tests được phát hiện | Đạt | Skip | Failure/error |
|---|---:|---:|---:|---:|
| platform-common | 13 | 13 | 0 | 0 |
| auth-service | 12 | 12 | 0 | 0 |
| inventory-service | 16 | 8 | 8 | 0 |
| payment-service | 20 | 15 | 5 | 0 |
| order-service | 16 | 13 | 3 | 0 |
| **Tổng** | **77** | **61** | **16** | **0** |

61 tests chạy gồm domain/use case, JWT được ký thật và JWKS loopback, MockMvc bearer authorization/error contract, Circuit Breaker qua HTTP loopback, retry/deadline/compensation và ArchUnit ranh giới Clean Architecture.

16 tests bị skip đều cần PostgreSQL thật qua Testcontainers. Docker client có trên host nhưng Docker engine không có pipe `docker_engine`; không có môi trường Docker hợp lệ. Vì vậy **chưa xác nhận runtime PostgreSQL concurrency, replica streaming, container startup, smoke/load/overload end-to-end hoặc throughput** trên máy này. Source integration test đã được compile cùng reactor.

Các kiểm tra bổ sung đã đạt:

- `docker compose config --quiet` cho base và base + demo override: YAML/variable merge hợp lệ.
- `bash -n` cho primary-init và replica-entrypoint: shell syntax hợp lệ; file LF.
- PowerShell parser cho smoke/load/overload: không có parse error.
- Review contract JSON Circuit Breaker từ thư viện Resilience4j 2.3.0, tên biến môi trường, datasource primary của Flyway và public-only JWKS.

Compose config không gọi được database hay chứng minh replication. Parser không chứng minh script đã chạy HTTP thành công. CI đã cấu hình Docker-enabled runner chạy verify, Compose, smoke/load và overload; workflow chưa được chạy từ workspace chưa có Git remote.

Để hoàn tất kiểm chứng runtime sau khi Docker Desktop Linux engine hoạt động:

```powershell
.\mvnw.cmd -B -ntp verify
docker compose up -d --build --wait --wait-timeout 240
.\scripts\smoke.ps1
.\scripts\load.ps1 -Requests 200 -Concurrency 32
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --wait --wait-timeout 240
.\scripts\overload.ps1
```

Chạy trên stack riêng với dữ liệu mẫu còn đủ hàng, không có script/người mua khác. Xem TESTING và OPERATIONS để đối chiếu ledger, stock, trạng thái Saga và WAL replication.
