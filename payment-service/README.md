# Payment service

Dịch vụ chạy cổng `8083`, sở hữu database PostgreSQL `paymentdb`. Đây là mô phỏng ví điện tử nội bộ để kiểm chứng tính đúng đắn của Saga; chưa tích hợp ngân hàng hoặc cổng thanh toán thật.

## Cấu trúc Clean Architecture và DDD

- `domain`: aggregate `Payment`, value object `Money`, quy tắc chuyển trạng thái và so khớp payload. Không phụ thuộc Spring/JDBC.
- `application`: `PaymentService` gọi các port `PaymentRepository`, `WalletRepository`, `PaymentLedger`; không dùng annotation Spring hoặc HTTP status. Lỗi nghiệp vụ là `PaymentFailure` thuần domain; web adapter ánh xạ reason thành HTTP status/code.
- `infrastructure/persistence`: adapter JDBC thực hiện SQL, row lock và lưu ledger.
- `infrastructure/web`: kiểm tra DTO, ánh xạ HTTP và lấy người dùng từ JWT.
- `infrastructure/TransactionPaymentFacade`: transaction bao quanh toàn bộ use case, timeout 5 giây. Không gọi HTTP bên trong transaction.

## Quy tắc thanh toán chính xác

`orderId` là khóa idempotency lâu dài. Retry phải dùng cùng `customerId`, `amount` và `currency`; số tiền `100` và `100.00` được xem là giống nhau. Khác payload trả `409 PAYMENT_PAYLOAD_CONFLICT`.

Luồng charge trong một transaction:

1. `INSERT ... ON CONFLICT DO NOTHING` tạo placeholder theo `orderId`. Khi request trùng chạy đồng thời, PostgreSQL chờ transaction đang sở hữu khóa kết thúc.
2. `SELECT ... FOR UPDATE` khóa payment. Nếu đã `CHARGED`, trả kết quả trước đó và không trừ thêm tiền.
3. Trừ số dư bằng một SQL nguyên tử: `UPDATE wallets SET balance=balance-amount WHERE balance>=amount`. Những order khác nhau dùng chung ví cũng không làm số dư âm.
4. Ghi `DEBIT` vào ledger và cập nhật `CHARGED`. Ledger có unique `(order_id,event_type)` làm lớp bảo vệ thứ hai.
5. Commit toàn bộ thay đổi cùng nhau. Thiếu tiền, timeout SQL hay lỗi ledger làm rollback cả placeholder, số dư và payment.

`void` cũng khóa payment trước khi hoàn tiền. Một payment đã charge chỉ có một `REFUND`; retry void không hoàn tiền lần hai. Void trên order chưa có payment tạo bản ghi `VOIDED` không có payload. Tombstone này ngăn request charge đến muộn sau khi order đã bị hủy: trả `409 PAYMENT_CANCELLED`. Nếu charge đang chạy trước, void chờ nó commit và hoàn tiền; nếu void chạy trước, charge bị từ chối. Không được xóa tombstone khi còn khả năng xuất hiện retry muộn.

Tiền sử dụng `BigDecimal` và PostgreSQL `NUMERIC(19,2)`; chỉ hỗ trợ `VND`, không dùng `double` hoặc làm tròn ngầm. Mọi thao tác số dư đọc/ghi trên primary để tránh replication lag ảnh hưởng quyết định tài chính.

## API

API nội bộ yêu cầu JWT có scope `internal`; API người mua yêu cầu scope `shop`. Microservice tự xác thực chữ ký JWT, issuer và audience qua cấu hình chung của `platform-common`.

| Method | Endpoint | Mục đích |
|---|---|---|
| POST | `/internal/payments/charge` | `{ "orderId": "uuid", "customerId": "alice", "amount": 50000, "currency": "VND" }` |
| POST | `/internal/payments/{orderId}/void` | Hủy/hoàn tiền idempotent, kể cả payment chưa tồn tại |
| GET | `/api/payments/{orderId}` | Người mua chỉ xem payment của mình; scope `admin` có thể xem tất cả |
| GET | `/api/wallets/me` | Xem số dư ví của JWT subject |

Response charge/void: `{ "orderId": "uuid", "customerId": "alice", "amount": 50000.00, "currency": "VND", "status": "CHARGED" }`. Status còn có `VOIDED`; void trước charge trả `customerId`, `amount`, `currency` bằng `null`.

Tài khoản ví seed: `alice=100000000`, `bob=100000000`, `poor=0`. User mới chưa có ví trả `409 INSUFFICIENT_FUNDS` khi charge; API xem ví trả `404 WALLET_NOT_FOUND`.

## Giới hạn tải và thử lỗi

Java 21 virtual threads xử lý request; Hikari giới hạn 16 connection, đợi connection tối đa 500 ms. PostgreSQL `lock_timeout=2000`, `statement_timeout=4000`, driver socket timeout 5 giây. Virtual thread không làm tăng capacity database; connection pool và giới hạn ở bên gọi vẫn cần thiết.

Demo fault được áp dụng trước transaction ở cả charge và void. Dịch vụ gọi API phải dùng Circuit Breaker/timeout và retry có backoff. Khi HTTP timeout, phía gọi không suy luận rằng payment chưa chạy: retry cùng orderId hoặc compensation bằng void. Saga của order-service có trách nhiệm retry compensation bền vững nếu payment-service tạm ngừng hoạt động.

## Kiểm thử

Từ thư mục reactor:

```powershell
cmd /c mvnw.cmd -pl payment-service -am test
```

Unit test kiểm tra value object, trạng thái, payload conflict và retry không trừ/hoàn tiền thêm. Test web giữ nguyên HTTP status/code của từng lỗi nghiệp vụ; ArchUnit kiểm tra domain/application không phụ thuộc Spring, HTTP common hoặc adapter JDBC/web. `PaymentConcurrencyTest` sử dụng PostgreSQL thật qua Testcontainers để kiểm tra 40 charge/void trùng đồng thời, cancel trước charge, rollback khi thiếu tiền và retry sau khi bổ sung tiền, nhiều order cạnh tranh cùng ví, race charge/cancel. Các test PostgreSQL tự skip nếu Docker không khả dụng; skip không chứng minh được row lock hoặc migration đã chạy thành công.

## Giới hạn triển khai thực tế

Với PSP thật, không thể bọc HTTP của PSP cùng transaction PostgreSQL. Cần provider idempotency key, webhook xác thực, trạng thái thanh toán chưa xác định và reconciliation bền vững; không thể coi HTTP timeout là thanh toán thất bại. Ledger hiện tại phục vụ audit ví demo, chưa phải sổ cái kế toán double entry. Tiền thật cần thiết kế sổ cái, kiểm soát truy cập, quản lý khóa và chính sách vận hành phù hợp.
