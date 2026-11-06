# Báo cáo test hàng loạt API order — 22/10/2026

**Kết quả đối soát dữ liệu: PASS, 502 kiểm tra đạt, 0 lỗi.** Lượt IPv4 có 7 response HTTP 503 tạm thời; đã recovery bằng đúng idempotency key/body và không tạo trùng đơn hoặc debit. Các 503 vẫn được giữ nguyên trong output, không loại khỏi thống kê.

Input có thể chạy lại: [batch-orders.input.json](../../examples/batch-orders.input.json). Output tổng hợp thực tế: [output.json](output.json). Hướng dẫn từng bước: [BATCH_ORDER_TEST.md](../../docs/BATCH_ORDER_TEST.md).

## Môi trường và phạm vi

- Windows, PowerShell 7.6.5, Java 21, Docker Desktop Linux engine 29.5.3; Docker cấp khoảng 3,8 GiB RAM.
- 4 service + PostgreSQL primary/replica. Project riêng `flashsale-batch-ip-20261022`; host ports 18081–18084, 15432–15433; dữ liệu bắt đầu từ seed mới.
- Dùng các image ứng dụng đã có, gắn alias dành riêng cho test; không sửa image/tag của stack chính. [containers.json](ipv4/containers.json) ghi image/container/health khi chạy. Mã nguồn hiện tại được `mvnw.cmd -B -ntp verify` kiểm tra riêng: **77/77 tests đạt, 0 skip**, gồm Testcontainers PostgreSQL thật; [maven-summary.json](maven-summary.json) và [JUnit XML](junit/).
- Các số HTTP dưới đây chỉ đếm **POST `/api/orders`**, không tính login, đọc catalog/ví hoặc GET polling.

## Input thực tế

[main.input.json](ipv4/main.input.json): alice, SKU Keyboard giá 99.000 VND, quantity 1, tồn ban đầu 100, ví ban đầu 100.000.000 VND.

| Tham số | Giá trị |
|---|---:|
| Ý định mua mới / key phân biệt | 200 |
| Request lặp cùng key/body | 100 |
| Tổng POST đợt đầu | 300 |
| Số request đồng thời tối đa | 32 |
| Probe đổi quantity nhưng dùng key cũ | 1 |
| Deadline của test | 240 giây |

100 request lặp được trộn với 200 request mới. Ví dụ key có dạng `batch-983c2c23c6e743af8b91a07dff7db965-1`; retry dùng lại đúng key, không tạo key mới. Giá, customer ID và currency của đơn không lấy từ payload client.

```json
{"productId":"11111111-1111-1111-1111-111111111111","quantity":1}
```

## Output quan sát được

| Kịch bản | POST đợt đầu | Response đợt đầu | Recovery POST | Probe | Đơn phân biệt | COMPLETED | REJECTED |
|---|---:|---|---:|---|---:|---:|---:|
| 200 mua + 100 gửi lặp | 300 | 293 × 202; 7 × 503 | 2 × 202 | 1 × 409 | 200 | 100 | 100 OUT_OF_STOCK |
| Poor, SKU còn 50 hàng | 20 | 20 × 202 | 0 | 1 × 409 | 10 | 0 | 10 INSUFFICIENT_FUNDS |
| SKU tồn 0 | 20 | 20 × 202 | 0 | 1 × 409 | 10 | 0 | 10 OUT_OF_STOCK |
| **Tổng** | **340** | **333 × 202; 7 × 503** | **2 × 202** | **3 × 409** | **220** | **100** | **120** |

Tổng thực gửi là **345 POST**: 340 đợt đầu + 2 recovery + 3 probe. Tổng HTTP status là 335 × 202, 7 × 503, 3 × 409. Không còn key chưa xác định kết quả hoặc đơn chưa terminal. 5 trong 7 response 503 thuộc các key đã có request trùng nhận 202; chỉ 2 key cần gửi thêm recovery.

**HTTP 202 là tiếp nhận đơn**, không phải mua thành công. 100 đơn OUT_OF_STOCK chỉ có kết quả cuối sau Saga compensation; các response 202 ban đầu vẫn hợp lệ.

Output JSON rút gọn từ [ipv4/main/summary.json](ipv4/main/summary.json):

```json
{
  "status": "PASS",
  "plannedBulkHttpRequests": 300,
  "actualBulkHttpRequests": 300,
  "actualRecoveryHttpRequests": 2,
  "actualConflictHttpRequests": 1,
  "actualHttpRequests": 303,
  "bulkHttpStatusCounts": {"202": 293, "503": 7},
  "distinctOrders": 200,
  "completedOrders": 100,
  "rejectedOrders": 100,
  "pendingOrders": 0,
  "rejectionReasons": {"OUT_OF_STOCK": 100},
  "duplicateIdMismatches": [],
  "observedConsistency": {
    "stockBefore": 100,
    "stockAfter": 0,
    "walletBefore": 100000000,
    "walletAfter": 90100000,
    "walletDebitedVnd": 9900000
  }
}
```

## Tồn kho, thanh toán và replica

| Dữ liệu | Trước | Sau | Đối chiếu |
|---|---:|---:|---|
| Tồn Keyboard | 100 | 0 | Đúng 100 đơn COMPLETED × 1 |
| Tồn Headphones | 50 | 50 | 10 đơn thiếu tiền đã release hết |
| Tồn Sold Out Mouse | 0 | 0 | Không giữ hàng hoặc debit |
| Ví alice | 100.000.000 | 90.100.000 | Trừ đúng 100 × 99.000 VND |
| Ví bob | 100.000.000 | 100.000.000 | Không đổi |
| Ví poor | 0 | 0 | Không debit khi thiếu tiền |
| DEBIT ledger | 0 | 100 | Mỗi đơn thành công đúng một debit |
| REFUND ledger | 0 | 0 | Lượt này chưa có debit nào cần refund; các đơn lỗi bị chặn trước debit |

Primary chứa đúng 220 orders, 220 reservations và 220 payments: 100 CONFIRMED/CHARGED, 120 CANCELLED/VOIDED. Không hàng/số dư âm, không row/ledger mồ côi hoặc ledger trùng. Tồn cộng lượng đang giữ/bán được bảo toàn. Replica ở recovery/read-only, streaming và catalog đã hội tụ với primary. Bằng chứng: [primary orders](ipv4/audit/final.orders.json), [inventory](ipv4/audit/final.inventory.json), [payment và ledger](ipv4/audit/final.payment.json), [replica](ipv4/audit/final.replica.json), [502 assertions](ipv4/audit/assertions.json).

Refund sau debit không được khẳng định từ lượt API này vì REFUND thực tế bằng 0. Trường hợp charge/refund cạnh tranh đã chạy trong các integration test PostgreSQL của payment-service.

## Timing và giới hạn tải phát hiện được

| Số đo client | Lượt chính IPv4 |
|---|---:|
| Gửi 300 POST | 13,207 giây |
| Chờ thêm recovery/probe/terminal sau đợt gửi | 21,445 giây |
| Tổng hai giai đoạn | 34,652 giây |
| Tổng script, gồm auth/snapshot/replica | 37,926 giây |
| POST p50 | 807,718 ms |
| POST p95 | 1.578,049 ms |
| POST p99 | 1.693,663 ms |

Percentile tính trên toàn bộ 300 POST đầu, có cả response 503. Đây là timing từ client PowerShell trên máy local, có overhead client/network và vòng chạy đầu; không phải benchmark server độc lập hoặc SLA production.

Log [order-service.log](ipv4/order-service.log) xác nhận hai nguồn lỗi tạm thời: Hikari connection acquisition timeout và PostgreSQL lock timeout tại `SELECT pg_advisory_xact_lock(73001)`. Các budget chờ hữu hạn đã trả 503, client retry cùng key giữ correctness. Admission lock toàn cục và việc dùng chung pool giữa API/worker là điểm cần đo và tối ưu tiếp nếu yêu cầu giảm p95/503; không tăng retry vô hạn hoặc chỉ tăng số thread.

Lượt đầu dùng localhost: POST p95 3.179,356 ms, không có 503; [summary cũ](main/summary.json). Đo read-only cùng health endpoint cho thấy localhost khoảng 2.056–2.624 ms, IPv4 127.0.0.1 khoảng 11–49 ms. Vì vậy lượt IPv4 được thực hiện lại trên **project/volume mới**; không lấy tồn cũ đã bán hết để tạo kết quả. Đây là quan sát local, chưa có network trace để kết luận nguyên nhân DNS/IPv6. Hai lượt không phải phép A/B kiểm soát mọi yếu tố warmup.

## Xem input/output từng request

- [Input manifest](ipv4/main/input.manifest.json), [từng POST đã gửi](ipv4/main/rawrequests.jsonl), [mọi response POST](ipv4/main/responses.jsonl).
- [Ví dụ cùng key, cùng order ID và conflict 409](ipv4/main/input-output.sample.json).
- [Ví dụ 503 → recovery cùng key/body → một order ID](ipv4/main/retry.sample.json).
- [200 đơn cuối dạng JSON](ipv4/main/orders.final.json), [CSV](ipv4/main/orders.final.csv), [GET polling](ipv4/main/polls.jsonl).
- [Kết quả thiếu tiền](ipv4/poor/summary.json), [kết quả hàng hết](ipv4/soldout/summary.json).

Output không có bearer token hoặc password. Demo credentials chỉ có trong file input mẫu để đăng nhập local.

Sau test, cả hai project test đã được dừng để trả cổng và RAM; volume/database và toàn bộ bằng chứng được giữ nguyên. Chọn project name mới khi chạy lại để có seed mới, theo [hướng dẫn](../../docs/BATCH_ORDER_TEST.md). Không thay đổi dữ liệu của stack chính.
