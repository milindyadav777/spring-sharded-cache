#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=app_password="$DB_PASSWORD" --set=repl_password="$REPL_PASSWORD" \
  --set=scaler_password="$SCALER_PASSWORD" <<'SQL'
CREATE ROLE catalog_app LOGIN PASSWORD :'app_password';
CREATE ROLE standby_reader LOGIN REPLICATION PASSWORD :'repl_password';
CREATE ROLE backlog_reader LOGIN PASSWORD :'scaler_password';
CREATE TABLE product (
  tenant bigint NOT NULL CHECK (tenant > 0),
  sku varchar(64) NOT NULL, version bigint NOT NULL CHECK (version > 0),
  name varchar(180) NOT NULL, PRIMARY KEY (tenant, sku)
);
CREATE TABLE cache_job (
  tenant bigint NOT NULL, sku varchar(64) NOT NULL,
  region text NOT NULL CHECK (region IN ('EAST','WEST')),
  version bigint NOT NULL, name varchar(180) NOT NULL,
  due_at timestamptz NOT NULL DEFAULT now(), attempts integer NOT NULL DEFAULT 0,
  token uuid, PRIMARY KEY (tenant, sku, region),
  FOREIGN KEY (tenant, sku) REFERENCES product
);
CREATE INDEX cache_job_due ON cache_job(due_at,tenant,sku,region);
GRANT SELECT, INSERT, UPDATE ON product TO catalog_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cache_job TO catalog_app;
GRANT SELECT (due_at) ON cache_job TO backlog_reader;
SQL
printf '%s\n' 'host replication standby_reader all scram-sha-256' >> "$PGDATA/pg_hba.conf"
