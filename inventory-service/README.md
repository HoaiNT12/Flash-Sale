# Inventory bounded context

Service chạy tại `http://localhost:8082`. `domain` chứa aggregate `Reservation`, quy tắc chuyển trạng thái và cửa sổ flash sale; `application` chứa use case và outbound port; `adapter` chứa HTTP/JDBC; `configuration` nối dependency. Domain và application không import Spring.

## Các API

| Method | URL | Scope | Kết quả |
|---|---|---|---|
| GET | `/api/products` | `shop` | Danh sách sản phẩm, giá và tồn kho hiển thị |
| GET | `/api/products/{productId}` | `shop` | Chi tiết sản phẩm |
| POST | `/internal/reservations` | `internal` | Giữ tồn kho, khóa giá server cho order |
| POST | `/internal/reservations/{orderId}/confirm` | `internal` | Xác nhận giữ hàng sau thanh toán |
| POST | `/internal/reservations/{orderId}/release` | `internal` | Hoàn tồn kho khi Saga bù trừ |

Body reserve: `{"orderId":"<uuid>","productId":"11111111-1111-1111-1111-111111111111","quantity":1}`. Response chứa `orderId`, `productId`, `quantity`, `unitPrice`, `currency`, `status`. Không nhận giá từ client. Reserve cùng `orderId` và cùng payload trả reservation trước đó; thay đổi product hoặc quantity trả `409 RESERVATION_CONFLICT`.

## Tính chính xác trong flash sale

Mỗi operation dùng một transaction PostgreSQL trên **primary**. Row reservation được tạo bằng `INSERT ... ON CONFLICT DO NOTHING` rồi khóa bằng `SELECT ... FOR UPDATE`. Việc khóa theo `orderId` khiến duplicate request/confirm/release chạy tuần tự cho cùng order. Tồn kho được giảm bằng một `UPDATE ... WHERE available_stock >= quantity AND sale_starts_at <= statement_timestamp() AND sale_ends_at > statement_timestamp() RETURNING *`; PostgreSQL kiểm tra lại điều kiện sau khi chờ row lock. `statement_timestamp()` kiểm tra cửa sổ bán hàng tại statement mua hàng, không dùng đồng hồ client. Constraint DB bắt buộc tồn kho không âm.

Giá và currency lấy từ row product được update thành công, rồi lưu cùng reservation trong cùng transaction. Domain và constraint database yêu cầu giá **lớn hơn 0**, phù hợp luồng charge ví demo. Lỗi sau khi giảm stock rollback cả hai bảng. `RESERVED` và `CONFIRMED` vẫn giữ stock; `release` có thể bù trừ cả hai và chỉ hoàn stock một lần. `CANCELLED` là trạng thái cuối. Nếu release đến trước reserve do request bị trễ, service tạo tombstone `CANCELLED`; reserve/confirm đến sau sẽ bị từ chối, tránh mất hàng do timeout rồi compensation chạy ngược thứ tự.

Không tự hết hạn reservation riêng lẻ khi order còn xử lý: coordinator Saga phải quyết định cancellation và retry compensation. Không xóa tombstone trong khi request cũ còn có thể được replay. Tồn kho hiển thị có thể trễ nếu đọc replica; **đặt hàng luôn xác nhận lại trên primary**.

## Database và giới hạn tải

Database `inventorydb`, account demo `inventory/inventory_dev`. Flyway tạo bảng, index và ba sản phẩm: Keyboard UUID `111...` tồn 100 giá 99.000 VND; Headphones `222...` tồn 50 giá 199.000 VND; Mouse `333...` tồn 0. Seed mở sale trong 365 ngày tính từ thời điểm migrate, tránh phụ thuộc ngày máy chạy.

`DB_URL`, `DB_USER`, `DB_PASSWORD` cấu hình primary. `CATALOG_DB_URL`, `CATALOG_DB_USER`, `CATALOG_DB_PASSWORD` bật datasource replica chỉ cho catalog. Bỏ trống `CATALOG_DB_URL` đọc catalog từ primary. Hikari primary tối đa 12 connection, chờ connection 2 giây. Cả primary và replica được cấu hình PostgreSQL connect timeout 2 giây, socket timeout 5 giây, cancel signal timeout 2 giây, DB lock timeout 2 giây và statement timeout 3 giây; truy vấn catalog cũng có giới hạn chờ. HTTP trả `503 INVENTORY_BUSY` với `Retry-After: 1` khi hết connection, lock timeout hoặc query timeout; caller retry cùng order id bằng backoff có jitter. Tồn kho hết/sale đóng/cancelled là lỗi business `409`, không retry mù quáng.

Servlet dùng virtual thread Java 21. Fault injection do `platform-common` cung cấp; `DemoFaults.beforeRequest()` chạy trước transaction cho reserve/confirm, tránh giữ connection/row lock khi mô phỏng mạng chậm. Release vẫn thực hiện bình thường để compensation phục hồi sau fault.

## Kiểm thử

`ArchitectureTest` dùng ArchUnit bảo vệ domain/application khỏi phụ thuộc Spring, JDBC, platform-common và adapter/configuration. `ReservationTest` kiểm tra cancellation cuối cùng, giữ giá khi retry, giá dương, request conflict, compensation và thời điểm bắt đầu/kết thúc sale. `JdbcReservationStoreIntegrationTest` dùng PostgreSQL thật qua Testcontainers, kiểm tra 160 buyer tranh 100 stock, 64 retry cùng order, 32 compensation đồng thời, release trước reserve, 48 reserve/release chạy đua, sale hết hạn, rollback out-of-stock và rollback lỗi ghi reservation sau khi stock đã giảm. Integration test tự skip nếu Docker không chạy; skip không phải bằng chứng về concurrency, cần chạy lại trên máy/CI có Docker.

Chạy từ root: `mvnw.cmd -pl inventory-service -am test`. Xem cấu hình, luồng Saga, replication và demo overload trong README ở root.
