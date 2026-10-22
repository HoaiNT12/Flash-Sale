SELECT jsonb_build_object(
    'database', current_database(),
    'snapshot_at', clock_timestamp(),
    'is_in_recovery', pg_is_in_recovery(),
    'receive_lsn', pg_last_wal_receive_lsn()::text,
    'replay_lsn', pg_last_wal_replay_lsn()::text,
    'last_transaction_replayed_at', pg_last_xact_replay_timestamp(),
    'last_replay_transaction_age_seconds', EXTRACT(EPOCH FROM clock_timestamp() - pg_last_xact_replay_timestamp()),
    'replay_paused', CASE WHEN pg_is_in_recovery() THEN pg_is_wal_replay_paused() ELSE NULL END,
    'products', COALESCE((SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) FROM products p), '[]'::jsonb)
);
