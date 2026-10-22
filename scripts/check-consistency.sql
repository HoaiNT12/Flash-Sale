-- Run each database section separately after all Saga work has settled. See docs/TESTING.md.
-- inventorydb: must return zero rows. Seed totals for this demo are 100 / 50 / 0.
SELECT p.id,p.available_stock,COALESCE(sum(r.quantity) FILTER (WHERE r.status IN ('RESERVED','CONFIRMED')),0) allocated
FROM products p LEFT JOIN reservations r ON r.product_id=p.id
GROUP BY p.id,p.available_stock
HAVING p.available_stock + COALESCE(sum(r.quantity) FILTER (WHERE r.status IN ('RESERVED','CONFIRMED')),0)
 <> CASE p.id::text WHEN '11111111-1111-1111-1111-111111111111' THEN 100 WHEN '22222222-2222-2222-2222-222222222222' THEN 50 ELSE 0 END;

-- paymentdb: must return zero rows. No external topups occur in this demo.
SELECT w.customer_id,w.balance,COALESCE(sum(CASE l.event_type WHEN 'DEBIT' THEN -l.amount ELSE l.amount END),0) net_change
FROM wallets w LEFT JOIN payment_ledger l ON l.customer_id=w.customer_id
GROUP BY w.customer_id,w.balance
HAVING w.balance <> CASE w.customer_id WHEN 'poor' THEN 0 ELSE 100000000 END
 + COALESCE(sum(CASE l.event_type WHEN 'DEBIT' THEN -l.amount ELSE l.amount END),0);

-- paymentdb: lifecycle and ledger totals must agree; zero rows expected.
SELECT p.order_id,p.status FROM payments p LEFT JOIN payment_ledger l ON l.order_id=p.order_id
GROUP BY p.order_id,p.status
HAVING (p.status='CHARGED' AND (count(*) FILTER (WHERE l.event_type='DEBIT') <> 1 OR count(*) FILTER (WHERE l.event_type='REFUND') <> 0))
 OR (p.status='VOIDED' AND count(*) FILTER (WHERE l.event_type='DEBIT') <> count(*) FILTER (WHERE l.event_type='REFUND'));

-- ordersdb: must return zero while the stack is idle and downstream services are healthy.
SELECT id,status,step,attempts,failure_code,lease_until,next_attempt FROM orders WHERE step <> 'FINISHED';
