WITH ids AS (
    SELECT value::uuid order_id FROM jsonb_array_elements_text(:'order_ids'::jsonb)
), totals AS (
    SELECT customer_id,
           COALESCE(sum(amount) FILTER (WHERE event_type = 'DEBIT'), 0) debits,
           COALESCE(sum(amount) FILTER (WHERE event_type = 'REFUND'), 0) refunds
    FROM payment_ledger GROUP BY customer_id
), scoped AS (
    SELECT p.* FROM payments p JOIN ids USING (order_id)
)
SELECT jsonb_build_object(
    'database', current_database(),
    'snapshot_at', clock_timestamp(),
    'is_primary', NOT pg_is_in_recovery(),
    'wallets', COALESCE((
        SELECT jsonb_agg(to_jsonb(w) || jsonb_build_object(
            'debited_total_global', COALESCE(t.debits, 0),
            'refunded_total_global', COALESCE(t.refunds, 0),
            'opening_balance_inferred', w.balance + COALESCE(t.debits, 0) - COALESCE(t.refunds, 0))
            ORDER BY w.customer_id)
        FROM wallets w LEFT JOIN totals t USING (customer_id)
    ), '[]'::jsonb),
    'payments', COALESCE((SELECT jsonb_agg(to_jsonb(p) ORDER BY p.order_id) FROM scoped p), '[]'::jsonb),
    'global_payment_count', (SELECT count(*) FROM payments),
    'global_ledger_event_count', (SELECT count(*) FROM payment_ledger),
    'unscoped_payment_order_ids', COALESCE((SELECT jsonb_agg(p.order_id ORDER BY p.order_id) FROM payments p
        WHERE NOT EXISTS (SELECT 1 FROM ids i WHERE i.order_id = p.order_id)), '[]'::jsonb),
    'unscoped_ledger_order_ids', COALESCE((SELECT jsonb_agg(orphan.order_id ORDER BY orphan.order_id) FROM (
        SELECT DISTINCT l.order_id FROM payment_ledger l
        WHERE NOT EXISTS (SELECT 1 FROM ids i WHERE i.order_id = l.order_id)
    ) orphan), '[]'::jsonb),
    'ledger', COALESCE((SELECT jsonb_agg(to_jsonb(l) ORDER BY l.id)
        FROM payment_ledger l JOIN ids USING (order_id)), '[]'::jsonb),
    'missing_order_ids', COALESCE((SELECT jsonb_agg(i.order_id ORDER BY i.order_id) FROM ids i
        WHERE NOT EXISTS (SELECT 1 FROM scoped p WHERE p.order_id = i.order_id)), '[]'::jsonb)
);
