#!/usr/bin/env bash
set -Eeuo pipefail

psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1 \
  --set auth_password="$AUTH_DB_PASSWORD" \
  --set inventory_password="$INVENTORY_DB_PASSWORD" \
  --set orders_password="$ORDERS_DB_PASSWORD" \
  --set payment_password="$PAYMENT_DB_PASSWORD" \
  --set replication_password="$REPLICATION_PASSWORD" <<'SQL'
CREATE ROLE auth LOGIN PASSWORD :'auth_password';
CREATE ROLE inventory LOGIN PASSWORD :'inventory_password';
CREATE ROLE orders LOGIN PASSWORD :'orders_password';
CREATE ROLE payment LOGIN PASSWORD :'payment_password';
CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD :'replication_password';
CREATE DATABASE authdb OWNER auth;
CREATE DATABASE inventorydb OWNER inventory;
CREATE DATABASE ordersdb OWNER orders;
CREATE DATABASE paymentdb OWNER payment;
REVOKE ALL ON DATABASE authdb FROM PUBLIC;
REVOKE ALL ON DATABASE inventorydb FROM PUBLIC;
REVOKE ALL ON DATABASE ordersdb FROM PUBLIC;
REVOKE ALL ON DATABASE paymentdb FROM PUBLIC;
GRANT CONNECT ON DATABASE authdb TO auth;
GRANT CONNECT ON DATABASE inventorydb TO inventory;
GRANT CONNECT ON DATABASE ordersdb TO orders;
GRANT CONNECT ON DATABASE paymentdb TO payment;
SQL

printf '\nhost replication replicator 0.0.0.0/0 scram-sha-256\nhost replication replicator ::/0 scram-sha-256\n' >> "$PGDATA/pg_hba.conf"
