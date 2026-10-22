-- Empty run_prefix intentionally selects all orders in the isolated project.
WITH scoped AS (
    SELECT * FROM orders WHERE starts_with(idempotency_key, :'run_prefix')
)
SELECT jsonb_build_object(
    'database', current_database(),
    'snapshot_at', clock_timestamp(),
    'is_primary', NOT pg_is_in_recovery(),
    'run_prefix', :'run_prefix',
    'orders', COALESCE((SELECT jsonb_agg(to_jsonb(o) ORDER BY o.created_at, o.id) FROM scoped o), '[]'::jsonb),
    'order_ids', COALESCE((SELECT jsonb_agg(id ORDER BY id) FROM scoped), '[]'::jsonb),
    'history', COALESCE((SELECT jsonb_agg(to_jsonb(h) ORDER BY h.id)
                       FROM order_history h JOIN scoped o ON o.id = h.order_id), '[]'::jsonb),
    'state_counts', COALESCE((SELECT jsonb_object_agg(status, n)
                             FROM (SELECT status, count(*) n FROM scoped GROUP BY status) s), '{}'::jsonb),
    'unfinished', (SELECT count(*) FROM scoped WHERE step <> 'FINISHED')
);
