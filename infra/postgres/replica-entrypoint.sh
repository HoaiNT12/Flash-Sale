#!/usr/bin/env bash
set -Eeuo pipefail

if [ "$(id -u)" = '0' ]; then
  mkdir -p "$PGDATA"
  chown postgres:postgres "$PGDATA"
  chmod 700 "$PGDATA"
  exec gosu postgres bash "$0" "$@"
fi

if [ -s "$PGDATA/PG_VERSION" ] && [ ! -f "$PGDATA/standby.signal" ]; then
  echo "Replica data directory is incomplete: PG_VERSION exists without standby.signal." >&2
  echo "Refusing writable startup. Restore a valid replica backup or rebuild only the replica-data volume." >&2
  exit 1
fi

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  # Reuse the physical slot after a replica-volume rebuild. The primary owns its lifecycle.
  until pg_isready -h postgres-primary -U replicator -d postgres; do
    sleep 2
  done
  psql -h postgres-primary -U replicator -d postgres -v ON_ERROR_STOP=1 -c "SELECT pg_create_physical_replication_slot('flashsale_replica') WHERE NOT EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = 'flashsale_replica')"
  pg_basebackup -h postgres-primary -p 5432 -U replicator -D "$PGDATA" -Fp -Xs -P --slot=flashsale_replica
  touch "$PGDATA/standby.signal"
  printf "\nprimary_conninfo = 'host=postgres-primary port=5432 user=replicator application_name=flashsale_replica passfile=/var/lib/postgresql/.pgpass'\n" >> "$PGDATA/postgresql.auto.conf"
  printf "primary_slot_name = 'flashsale_replica'\n" >> "$PGDATA/postgresql.auto.conf"
fi

# Avoid embedding the replication secret into postgresql.auto.conf or process arguments.
replication_password=${PGPASSWORD//\\/\\\\}
replication_password=${replication_password//:/\\:}
printf 'postgres-primary:5432:replication:replicator:%s\n' "$replication_password" > /var/lib/postgresql/.pgpass
chmod 600 /var/lib/postgresql/.pgpass
unset PGPASSWORD
exec postgres -c hot_standby=on
