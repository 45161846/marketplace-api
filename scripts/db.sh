#!/usr/bin/env bash
set -eu

cd "$(dirname "$0")/.."
set -a
source .env
set +a

pg=${PG_BIN:-/opt/homebrew/opt/postgresql@17/bin}
mode=${1:-start}

if [ "$mode" = stop ]; then
    "$pg/pg_ctl" -D .local/pg stop
    exit 0
fi

if [ "$mode" != start ]; then
    printf '%s\n' 'Usage: scripts/db.sh [start|stop]' >&2
    exit 1
fi

mkdir -p .local/socket
chmod 700 .local

if [ ! -s .local/pg/PG_VERSION ]; then
    umask 077
    printf '%s\n' "$DB_PASSWORD" > .local/db.pass
    "$pg/initdb" -D .local/pg -U "$DB_USER" --auth-local=trust --auth-host=scram-sha-256 --pwfile=.local/db.pass --locale=C -E UTF8
    rm .local/db.pass
fi

if ! "$pg/pg_ctl" -D .local/pg status >/dev/null 2>&1; then
    "$pg/pg_ctl" -D .local/pg -l .local/pg.log -o "-h 127.0.0.1 -p 55432 -k $PWD/.local/socket" start
fi

export PGPASSWORD="$DB_PASSWORD"
for db in marketplace marketplace_test; do
    exists=$("$pg/psql" -h 127.0.0.1 -p 55432 -U "$DB_USER" -d postgres -Atqc "SELECT 1 FROM pg_database WHERE datname = '$db'")
    if [ "$exists" != 1 ]; then
        "$pg/createdb" -h 127.0.0.1 -p 55432 -U "$DB_USER" "$db"
    fi
done
