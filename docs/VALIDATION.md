# Kết quả kiểm chứng

## Cập nhật 31/10/2026: JMeter Circuit Breaker

Đã bổ sung [hướng dẫn JMeter](JMETER_CIRCUIT_BREAKER.md), Test Plan native `tests/jmeter/circuit-breaker.jmx`, Groovy điều phối và cấu hình lưu JTL an toàn. Kiểm chứng bằng **Apache JMeter 5.6.3, Java 21.0.11, Windows PowerShell 5.1.19041.6456**. Binary tải từ Apache và đối chiếu SHA-512 trước khi chạy.

Lượt thật trên Compose project riêng `flashsale-jmeter-20261031093054`, dữ liệu seed mới, bật demo faults Inventory:

- 8 threads × 4 vòng tạo 32 ý định mua, thêm 3 đơn probe phục hồi. Tổng 35 checkout POST, tất cả trả HTTP 202 và 35 order ID duy nhất.
- Quan sát sự kiện Inventory breaker trong chính lượt chạy: CLOSED_TO_OPEN lúc **16:37:20.803**, OPEN_TO_HALF_OPEN lúc **16:37:30.800**, HALF_OPEN_TO_CLOSED lúc **16:37:31.484**, ngày 31/10/2026, Asia/Bangkok.
- Trạng thái đầu và cuối đều CLOSED; quan sát được NOT_PERMITTED khi circuit mở. Observer không có lỗi; các sự kiện được thu liên tục, không chỉ đọc một lần ở cuối.
- **35 COMPLETED, 0 REJECTED, 0 pending**, có healthy probes hoàn thành; `summary.status=PASS`, không có failure.
- TearDown reset fault thành công HTTP 200 với delayMs=0, fail=false. Lệnh dừng stack test chưa được xác nhận vì Docker Desktop Linux Engine trả HTTP 500 ở bước cleanup. Volumes vẫn được giữ; không thay dữ liệu stack chính.
- JTL ghi 42 sampler: gồm HTTP login/checkout và các sampler điều phối có chờ. Không dùng p95 gộp của 42 sampler để diễn giải latency checkout.
- Kiểm tra cấu hình không hợp lệ: summary FAIL và không tạo checkout; xuất CSV có header và dashboard HTML hợp lệ. Không tìm thấy mật khẩu mẫu, JWT hay Bearer header trong summary/JTL/log đã lưu.

HTTP 202 bên ngoài vẫn thành công trong lúc lời gọi từ worker tới Inventory gặp fault 503. Bằng chứng breaker nằm ở Actuator state/events và các đơn sau phục hồi, không ở tỷ lệ lỗi POST order. Kết quả nằm trong `.local/jmeter-validation/run-20261031-093700/summary.json` và các artifact cùng folder. Lượt này dùng lại service images có sẵn bằng `--no-build`; không chạy lại Maven verify hoặc khẳng định image vừa rebuild. Số liệu là kịch bản local có lỗi mô phỏng, không phải SLA production hay bài đối soát toàn bộ ledger.

## Cập nhật 31/10/2026: Windows PowerShell 5.1

Đã cập nhật [quy trình chạy từ đầu](RUN_POWERSHELL_5_1.md), README và hướng dẫn batch/test cho Windows PowerShell **5.1**. Các script dùng helper JSON/HTTP và runspace pool, không còn phụ thuộc `ForEach-Object -Parallel`, `ConvertFrom-Json -AsHashtable/-Depth`, `Invoke-WebRequest -SkipHttpErrorCheck` hoặc chế độ native argument của PowerShell 7.3. Audit truyền giá trị JSON qua stdin dạng hex rồi khôi phục bằng psql, giữ nguyên UUID và Unicode trên PowerShell 5.1.

Kiểm chứng bằng **Windows PowerShell 5.1.19041.6456** thật, không chỉ parser:

- Parse các script và code block PowerShell trong hướng dẫn: không có lỗi cú pháp.
- Helper: 25 kiểm tra đạt trên PowerShell 5.1 và 25 đạt trên PowerShell 7.6.5. Bao gồm JSON mảng rỗng/một phần tử/null, HTTP 409/503 giữ đúng body/status/headers, UTF-8, lỗi kết nối, timeout và giới hạn xử lý song song. Fixture HTTP quan sát được 6 lời gọi đồng thời khi throttle là 6.
- Batch fixture: 15 kiểm tra đạt trên PowerShell 5.1, gồm lỗi 503, timeout, recovery cùng key/body và probe 409. JSON/JSONL mới dùng UTF-8 không BOM và không chứa password/token.
- Verifier: cả hai bộ dữ liệu đã ghi ngày 22/10, mỗi bộ 220 đơn và 502 kiểm tra, đều đạt trên PowerShell 5.1 và 7.6.5. Bài thử sửa số dư 0,01 bị phát hiện. Fixture native giữ nguyên 220 UUID, Unicode/quotes/newline, mảng rỗng và khôi phục console encoding.

Đã dựng stack Docker **riêng** `flashsale-ps51-20261031085038`, dùng dữ liệu seed mới và chạy batch + audit + verifier bằng PowerShell 5.1. Kết quả:

| Chỉ số | Kết quả |
|---|---:|
| Bulk checkout POST | 300 |
| Recovery POST cùng key/body | 17 |
| Probe xung đột | 1 |
| Tổng checkout POST | 318 |
| HTTP 202 / 503 / 409 | 272 / 45 / 1 |
| Đơn duy nhất | 200 |
| COMPLETED / REJECTED / pending | 100 / 100 / 0 |
| Tồn SKU đầu | 100 về 0 |
| Ví alice | 100.000.000 về 90.100.000 VND |
| DEBIT ledger | 100 |
| Kiểm tra đối soát primary, replica và ledger | 446 đạt, 0 lỗi |

45 phản hồi 503 tạm thời vẫn được giữ trong output. Các key chưa nhận kết quả cần tổng cộng 17 recovery POST; sau phục hồi, mọi key có một order ID và không có debit trùng. 446 kiểm tra thuộc lượt chính 200 đơn, khác với 502 kiểm tra của báo cáo ba kịch bản ngày 22/10. Không thay số liệu lịch sử bằng kết quả mới.

Bằng chứng mới nằm tại `.local/ps51-validation/`, gồm `docker-run/main/summary.json`, `docker-run/assertions.json`, snapshot SQL và các fixture report. Stack kiểm thử đã dừng và giữ named volumes; dữ liệu stack chính không bị sửa. Lượt này dùng lại service images có sẵn với `--no-build` vì chỉ sửa tài liệu/script, không sửa Java. Không chạy lại Maven verify, standalone smoke/load/overload với Docker trong lượt này. Các số trên kiểm chứng tương thích script và tính đúng đắn trong tải local, không phải SLA production hoặc xác nhận image vừa rebuild.

## Cập nhật 22/10/2026

Docker Desktop Linux engine đã hoạt động. Chạy lại `mvnw.cmd -B -ntp verify`: **77 tests đạt, 0 failures, 0 errors, 0 skip**, bao gồm tất cả 16 integration test PostgreSQL thật từng bị skip ở đợt trước.

Đã gửi API order thật trên hai stack test cô lập. Lượt IPv4: 345 checkout POST (340 bulk + 2 recovery + 3 conflict probes), 220 đơn phân biệt, 100 COMPLETED, 120 REJECTED; **502 assertion đối soát primary/replica/stock/wallet/payment/ledger đạt**. Có 7 HTTP 503 tạm thời do pool/lock timeout; retry cùng key/body khôi phục kết quả mà không tạo đơn/debit trùng. Chi tiết input, output, timing và giới hạn được giữ trong [báo cáo](../test-results/2026-10-22-orders/REPORT.md), [output tổng hợp](../test-results/2026-10-22-orders/output.json) và [hướng dẫn chạy lại](BATCH_ORDER_TEST.md).

Lượt test không khẳng định automatic failover, thanh toán PSP thật hoặc throughput production. Các container test đã dừng, volumes và bằng chứng vẫn được giữ.

## Lịch sử 14/10/2026

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
