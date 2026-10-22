WITH ids AS (
    SELECT value::uuid order_id FROM jsonb_array_elements_text(:'order_ids'::jsonb)
), held AS (
    SELECT product_id,
           COALESCE(sum(quantity) FILTER (WHERE status IN ('RESERVED', 'CONFIRMED')), 0) held_quantity
    FROM reservations GROUP BY product_id
), scoped AS (
    SELECT r.* FROM reservations r JOIN ids USING (order_id)
)
SELECT jsonb_build_object(
    'database', current_database(),
    'snapshot_at', clock_timestamp(),
    'is_primary', NOT pg_is_in_recovery(),
    'wal_lsn', pg_current_wal_lsn()::text,
    'products', COALESCE((
        SELECT jsonb_agg(to_jsonb(p) || jsonb_build_object(
            'held_quantity_global', COALESCE(h.held_quantity, 0),
            'conservation_total', p.available_stock + COALESCE(h.held_quantity, 0)) ORDER BY p.id)
        FROM products p LEFT JOIN held h ON h.product_id = p.id
    ), '[]'::jsonb),
    'reservations', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.order_id) FROM scoped r), '[]'::jsonb),
    'global_reservation_count', (SELECT count(*) FROM reservations),
    'unscoped_order_ids', COALESCE((SELECT jsonb_agg(r.order_id ORDER BY r.order_id) FROM reservations r
        WHERE NOT EXISTS (SELECT 1 FROM ids i WHERE i.order_id = r.order_id)), '[]'::jsonb),
    'global_reservation_counts', COALESCE((SELECT jsonb_object_agg(status, n)
        FROM (SELECT status, count(*) n FROM reservations GROUP BY status) s), '{}'::jsonb),
    'missing_order_ids', COALESCE((SELECT jsonb_agg(i.order_id ORDER BY i.order_id) FROM ids i
        WHERE NOT EXISTS (SELECT 1 FROM scoped r WHERE r.order_id = i.order_id)), '[]'::jsonb),
    'replication', COALESCE((SELECT jsonb_agg(jsonb_build_object(
        'application_name', application_name, 'state', state, 'sync_state', sync_state,
        'sent_lsn', sent_lsn::text, 'write_lsn', write_lsn::text, 'flush_lsn', flush_lsn::text,
        'replay_lsn', replay_lsn::text,
        'replay_distance_bytes', pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)) ORDER BY application_name)
        FROM pg_stat_replication), '[]'::jsonb)
);
