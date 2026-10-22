-- Run only after flashsale_replica is connected. Writes wait if that replica stops.
ALTER SYSTEM SET synchronous_standby_names = 'FIRST 1 (flashsale_replica)';
ALTER SYSTEM SET synchronous_commit = 'on';
SELECT pg_reload_conf();
