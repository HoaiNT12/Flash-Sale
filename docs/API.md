# API contract

Base URL: auth 8081, inventory 8082, payment 8083, order 8084. Endpoint ngoài health/JWKS/login/service-token cần `Authorization: Bearer <JWT>`. Không gửi password, token hoặc service secret vào logs. JSON dùng camelCase; tiền dùng số thập phân, currency VND.

| Service | Method/path | Scope | Ý nghĩa |
|---|---|---|---|
| Auth | POST `/api/auth/login` | Public + rate limit | Customer/admin token |
| Auth | POST `/api/auth/service-token` | Client credentials + rate limit | Machine token |
| Auth | GET `/.well-known/jwks.json` | Public | Public verification keys |
| Inventory | GET `/api/products` | shop | Danh sách; đọc replica trong Compose |
| Inventory | GET `/api/products/{productId}` | shop | Giá/tồn hiển thị có thể stale |
| Order | POST `/api/orders` | shop | Lưu checkout; yêu cầu Idempotency-Key |
| Order | GET `/api/orders/{orderId}` | shop + owner | Trạng thái từ primary |
| Payment | GET `/api/wallets/me` | shop | Số dư primary theo JWT subject |
| Payment | GET `/api/payments/{orderId}` | shop + owner/admin | Thanh toán của đơn |
| Inventory | POST `/internal/reservations` | internal | Reserve + quote |
| Inventory | POST `/internal/reservations/{orderId}/confirm` | internal | Confirm idempotent |
| Inventory | POST `/internal/reservations/{orderId}/release` | internal | Release/tombstone idempotent |
| Payment | POST `/internal/payments/charge` | internal | Charge idempotent |
| Payment | POST `/internal/payments/{orderId}/void` | internal | Void/refund/tombstone idempotent |
| Inventory/payment | GET/POST `/demo/faults` | admin, demo enabled | Đọc/đặt fault trong RAM |
| Mọi service | GET `/actuator/health` | Public | Health summary |
| Order | GET `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents`, `/actuator/prometheus` | admin | Trạng thái/metrics Circuit Breaker |

## Đăng nhập và machine token

```json
{"username":"alice","password":"Demo@123"}
```

```json
{"clientId":"order-service","clientSecret":"order_dev_secret"}
```

Response token:

```json
{"accessToken":"<signed-JWT>","tokenType":"Bearer","expiresIn":900}
```

Login custom của demo chỉ phát access token, chưa cung cấp OAuth2 Authorization Server đầy đủ, refresh token hoặc đăng ký. Token machine được order-service cache và gia hạn trước expiry 30 giây; downstream 401 vô hiệu hóa cache để lần retry sau lấy token mới.

## Checkout

Header `Idempotency-Key`: 8..128 ký tự ASCII chữ/số/`_.:-`, scope theo customer JWT subject. CustomerId, giá và currency không lấy từ body client.

```json
{"productId":"11111111-1111-1111-1111-111111111111","quantity":1}
```

HTTP 202 có header `Location: /api/orders/{id}` và `Retry-After: 1`. Response/GET có các field `id`, `customerId`, `productId`, `quantity`, `unitPrice`, `amount`, `currency`, `status`, `step`, `attempts`, `deadline`, `failureCode`, `createdAt`, `updatedAt`. Price/amount ban đầu null, được persist sau reserve. Terminal status là COMPLETED hoặc REJECTED; COMPENSATING còn đang khôi phục tác động đã xảy ra.

Quantity hợp lệ 1..5. Retry cùng key/body trả cùng id kể cả đơn đã terminal. Key cũ/body khác trả 409 IDEMPOTENCY_CONFLICT. Không tìm thấy hoặc không sở hữu đơn trả 404, không tiết lộ sự tồn tại đơn của khách khác.

## API nội bộ

Reserve:

```json
{"orderId":"<UUID>","productId":"11111111-1111-1111-1111-111111111111","quantity":1}
```

Response gồm `orderId`, `productId`, `quantity`, `unitPrice`, `currency`, `status` RESERVED/CONFIRMED. Client không tự gọi reserve để đặt hàng; các command nội bộ chỉ phục vụ Saga đã được authenticate.

Charge:

```json
{"orderId":"<UUID>","customerId":"alice","amount":99000.00,"currency":"VND"}
```

Response gồm `orderId`, `customerId`, `amount`, `currency`, `status` CHARGED/VOIDED. Confirm/release/void không yêu cầu body nghiệp vụ; `{}` được chấp nhận. Tombstone hủy trước khi có dữ liệu có thể có payload null.

## Lỗi và retry của client

Body lỗi nghiệp vụ có ít nhất `code`, `message`; error chung thêm timestamp. Spring Security trả 401/403 theo bearer-token filter, có thể không cùng JSON envelope. Đừng retry 4xx dữ liệu/quyền với cùng payload để mong thành công.

| HTTP/code | Cách xử lý |
|---|---|
| 400 INVALID_REQUEST/INVALID_IDEMPOTENCY_KEY | Sửa input |
| 401/403 | Đăng nhập/kiểm tra scope; không dùng customer token cho internal API |
| 404 ORDER_NOT_FOUND/PRODUCT_NOT_FOUND | Kiểm tra ID và chủ sở hữu |
| 409 IDEMPOTENCY_CONFLICT/RESERVATION_CONFLICT/PAYMENT_PAYLOAD_CONFLICT | Không đổi payload của một ID/key đã dùng |
| 409 OUT_OF_STOCK/SALE_CLOSED/INSUFFICIENT_FUNDS | Saga chuyển compensation; GET đơn để biết kết quả cuối |
| 429 QUEUE_FULL hoặc auth rate limit | Backoff rồi thử lại; checkout giữ nguyên key |
| 503 TEMPORARILY_UNAVAILABLE/INVENTORY_BUSY | Kết quả có thể chưa rõ; backoff, giữ key; nếu đã có id thì GET |

HTTP 202 của checkout không chuyển thành HTTP 409 khi worker phát hiện thiếu tiền; lỗi đó xuất hiện trong `failureCode` của đơn sau compensation. Retry client không tạo order mới nếu chưa biết order cũ đã được tiếp nhận hay chưa.
