# Gửi hàng loạt API order: input, output và cách chạy lại

Ngày thực hiện: **22/10/2026**, Asia/Bangkok. Kết quả và dữ liệu thực tế nằm trong [báo cáo chạy test](../test-results/2026-10-22-orders/REPORT.md). Bài test sử dụng stack Docker riêng, dữ liệu seed mới và ví mô phỏng PostgreSQL.

## Input

File cấu hình: [examples/batch-orders.input.json](../examples/batch-orders.input.json).

```json
{
  "baseUrls": {
    "auth": "http://127.0.0.1:18081",
    "inventory": "http://127.0.0.1:18082",
    "payment": "http://127.0.0.1:18083",
    "order": "http://127.0.0.1:18084"
  },
  "customer": {"username": "alice", "password": "Demo@123"},
  "productId": "11111111-1111-1111-1111-111111111111",
  "quantity": 1,
  "uniqueOrders": 200,
  "duplicateRequests": 100,
  "concurrency": 32,
  "timeoutSeconds": 240
}
```

`uniqueOrders=200` tạo 200 ý định mua với 200 idempotency key khác nhau. `duplicateRequests=100` gửi lại cùng key và cùng body của 100 ý định đầu. Các request được trộn thứ tự, gửi tối đa 32 lời gọi cùng lúc. Request lặp có thể tới trước request mang nhãn gốc; cả hai vẫn phải trả cùng order ID.

Mỗi POST có dạng:

```http
POST http://127.0.0.1:18084/api/orders
Authorization: Bearer <token lấy từ auth-service>
Idempotency-Key: batch-<runId>-1
Content-Type: application/json

{"productId":"11111111-1111-1111-1111-111111111111","quantity":1}
```

Sau 300 POST, script gửi thêm **1 probe**: dùng key đã nhận nhưng đổi quantity thành 2, kỳ vọng HTTP 409 IDEMPOTENCY_CONFLICT. Vì vậy lượt chính có **301 checkout POST**, không tính login và GET polling. Nếu timeout/503/429, các recovery POST giữ nguyên key/body và được đếm riêng; không tạo ý định mua mới bằng key khác.

## Output

Mỗi lượt chạy tạo một folder mới. Không ghi password hoặc bearer token vào output.

| File | Nội dung |
|---|---|
| `input.manifest.json` | Cấu hình đã chạy, runId, key mapping; bỏ credentials |
| `rawrequests.jsonl` | Input từng checkout POST thực sự gửi: URL, key, body, timestamp |
| `responses.jsonl` | HTTP status, response body nguyên văn/đã parse, latency mỗi POST |
| `polls.jsonl` | GET trạng thái đến khi terminal; có timestamp/HTTP/body |
| `reads.jsonl` | Snapshot ví/catalog trước và sau test |
| `orders.final.json` | Các đơn phân biệt và trạng thái quan sát cuối |
| `summary.json` | Tổng HTTP, số đơn, lỗi nghiệp vụ, timing và kết quả assertion |

Kỳ vọng với dữ liệu mới: 300 POST được nhận (202), 200 đơn phân biệt, 100 COMPLETED, 100 REJECTED/OUT_OF_STOCK; tồn SKU đầu 100 → 0; ví alice 100.000.000 → 90.100.000 VND; probe 409. `202` chỉ xác nhận đã nhận đơn, không phải thanh toán thành công. Kết quả cuối phải lấy từ GET và audit database.

`requestLatencyMs.p50/p95/p99` đo từng POST từ client. `submissionDurationMs` đo đợt gửi, `completionDurationMs` đo thời gian chờ thêm sau đợt gửi (gồm recovery/probe), `submissionAndCompletionDurationMs` là tổng hai giai đoạn. `totalDurationMs` còn bao gồm đăng nhập, snapshot và đối chiếu replica. Không nhầm các số này với thời gian xử lý một đơn hoặc SLA của server.

## Chạy lại từ đầu

Cần Docker Desktop Linux engine và **PowerShell 7.3+**. Docker Compose 2.24.4+ hỗ trợ `!override` để thay cổng mà không mở thêm cổng gốc. [Tài liệu Compose merge](https://docs.docker.com/reference/compose-file/merge/).

```powershell
Set-Location 'C:\Users\Admin\OneDrive\Documents\java\FlashSale\FlashSale'

# Mỗi lần test chọn project mới để có database/volume seed mới.
$project = 'flashsale-batch-' + (Get-Date -Format 'yyyyMMddHHmmss')
$out = "test-results/$project"
$compose = @('compose','--env-file','.env.example','-p',$project,
             '-f','docker-compose.yml','-f','docker-compose.batch-test.yml')

docker @compose up -d --build --wait --wait-timeout 240
docker @compose ps

# Snapshot primary trước khi gửi API.
.\scripts\audit-batch.ps1 -ComposeProject $project -OutputDirectory "$out/audit" -Phase baseline

# 300 request đồng thời + 1 probe 409.
.\scripts\batch-orders.ps1 -InputFile examples/batch-orders.input.json -OutputDirectory "$out/main"

# Snapshot primary và replica sau khi các đơn đã kết thúc.
.\scripts\audit-batch.ps1 -ComposeProject $project -OutputDirectory "$out/audit" -Phase final

# Đối chiếu HTTP, order, reservation, payment, ledger, wallet và replica.
.\scripts\verify-batch.ps1 -AuditDirectory "$out/audit" -RunDirectories @("$out/main") -OutputFile "$out/assertions.json"

Get-Content "$out/main/summary.json"
Get-Content "$out/assertions.json"
```

Nếu cổng 18081–18084 hoặc 15432–15433 đang có stack test khác chiếm, dừng đúng project cũ trước; đổi tên project chỉ tách network/volume, không tự tách published ports. Dùng cùng biến `$compose` để xem logs và dừng stack vừa tạo:

```powershell
docker @compose logs --tail=100
docker @compose stop
```

`stop` giữ dữ liệu để audit. Không dùng lại project đã bán hết hàng rồi kỳ vọng lại có 100 đơn thành công. Không cần xóa volume để tạo dữ liệu mới: chọn project name mới. `OutputDirectory` của mỗi lượt API phải trống để không ghi đè bằng chứng cũ.

## Chạy thêm thiếu tiền và hàng đã hết

Sau lượt chính, SKU thứ hai vẫn còn 50 hàng và poor có số dư 0. Tạo input riêng từ cấu hình mẫu:

```powershell
$poor = Get-Content examples/batch-orders.input.json -Raw | ConvertFrom-Json
$poor.customer.username = 'poor'
$poor.productId = '22222222-2222-2222-2222-222222222222'
$poor.uniqueOrders = 10
$poor.duplicateRequests = 10
$poor.concurrency = 16
$poor | ConvertTo-Json -Depth 8 | Set-Content "$out/poor.input.json" -Encoding utf8
.\scripts\batch-orders.ps1 -InputFile "$out/poor.input.json" -OutputDirectory "$out/poor"

$soldout = Get-Content examples/batch-orders.input.json -Raw | ConvertFrom-Json
$soldout.productId = '33333333-3333-3333-3333-333333333333'
$soldout.uniqueOrders = 10
$soldout.duplicateRequests = 10
$soldout.concurrency = 16
$soldout | ConvertTo-Json -Depth 8 | Set-Content "$out/soldout.input.json" -Encoding utf8
.\scripts\batch-orders.ps1 -InputFile "$out/soldout.input.json" -OutputDirectory "$out/soldout"

# Cập nhật snapshot final sau cả ba lượt rồi verify tất cả.
.\scripts\audit-batch.ps1 -ComposeProject $project -OutputDirectory "$out/audit" -Phase final
.\scripts\verify-batch.ps1 -AuditDirectory "$out/audit" -RunDirectories @("$out/main","$out/poor","$out/soldout") -OutputFile "$out/assertions.json"
```

Kỳ vọng mỗi lượt phụ có 20 POST được nhận, 10 đơn phân biệt bị từ chối và 1 probe 409. Poor: INSUFFICIENT_FUNDS, số dư vẫn 0, hàng SKU thứ hai hoàn về 50. Soldout: OUT_OF_STOCK, không debit. Sau cả ba lượt có 220 đơn phân biệt, 100 COMPLETED, 120 REJECTED và 100 DEBIT ledger, không còn bước Saga pending.

## Audit và giới hạn kết luận

Collector lấy order IDs từ database theo run prefix hoặc toàn project test, không chỉ dựa vào HTTP response: timeout có thể xảy ra sau commit. Inventory/payment luôn được audit trên primary. Verifier kiểm tra:

- Mỗi idempotency key trả một order ID; HTTP 202 khớp row database; cùng key khác body trả 409.
- Đơn COMPLETED có reservation CONFIRMED, payment CHARGED và đúng một DEBIT khớp amount/customer/currency.
- Đơn REJECTED có reservation CANCELLED, payment VOIDED; ledger không debit hoặc có cặp debit/refund bằng nhau.
- Không record mồ côi, không ledger trùng, không tồn/số dư âm; tồn và số dư trước/sau khớp tổng đơn thành công.
- Replica read-only, đang streaming và dữ liệu sản phẩm đã hội tụ với primary.

Bài test kiểm tra correctness với ví mô phỏng và tải local hữu hạn. Không chứng minh throughput production, giao dịch PSP thật hoặc automatic failover. Quan sát localhost/IPv4 trong báo cáo là số đo của máy này; chưa tự kết luận nguyên nhân DNS/IPv6 nếu chưa có network trace.
