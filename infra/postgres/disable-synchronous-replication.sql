ALTER SYSTEM SET synchronous_standby_names = '';
SELECT pg_reload_conf();
