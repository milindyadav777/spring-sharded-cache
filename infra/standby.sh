#!/bin/sh
set -eu
if [ "$(id -u)" = 0 ]; then
  install -d -m 0700 -o postgres -g postgres "$(dirname "$PGDATA")"
  exec gosu postgres sh "$0"
fi
umask 077
# The lab secret generator produces hexadecimal passwords, safe in a pgpass file.
printf '%s:5432:*:standby_reader:%s\n' "$PRIMARY_HOST" "$REPL_PASSWORD" > /tmp/standby.pgpass
export PGPASSFILE=/tmp/standby.pgpass
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  stage="$PGDATA.bootstrap"
  # Only remove an unfinished bootstrap in the dedicated standby volume.
  rm -rf "$stage"
  until pg_isready -qh "$PRIMARY_HOST"; do sleep 2; done
  pg_basebackup -h "$PRIMARY_HOST" -U standby_reader -D "$stage" -X stream --no-password
  touch "$stage/standby.signal"
  printf "primary_conninfo = 'host=%s port=5432 user=standby_reader passfile=/tmp/standby.pgpass'\n" \
    "$PRIMARY_HOST" >> "$stage/postgresql.auto.conf"
  mv "$stage" "$PGDATA"
fi
test -f "$PGDATA/standby.signal"
exec postgres -D "$PGDATA" -c hot_standby=on
