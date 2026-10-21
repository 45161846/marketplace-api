#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
mode=${1:-up}
pod=marketplace-api
db=marketplace-db
api=marketplace-app
image=localhost/marketplace-api:latest

case "$mode" in
    logs) exec podman logs -f "$api" ;;
    status) exec podman ps -a --filter "pod=$pod" ;;
    stop) exec podman pod stop "$pod" ;;
    down) exec podman pod rm --force --ignore "$pod" ;;
    up|seed|test) ;;
    *) printf '%s\n' 'Usage: scripts/podman.sh [up|seed|test|logs|status|stop|down]' >&2; exit 1 ;;
esac

set -a
source .env
set +a
: "${DB_USER:?Set DB_USER in .env}"
: "${DB_PASSWORD:?Set DB_PASSWORD in .env}"
: "${JWT_SECRET:?Set JWT_SECRET in .env}"
port=${PORT:-8080}
export PORT=8080
export DB_URL=jdbc:postgresql://127.0.0.1:5432/marketplace
export TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/marketplace_test
envs=(--env DB_URL --env DB_USER --env DB_PASSWORD --env JWT_SECRET --env PORT --env ORDER_INTERVAL_MINUTES)

ready_db() {
    for ((i=0; i<60; i++)); do
        if podman exec "$db" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d marketplace; then
            return
        fi
        sleep 1
    done
    podman logs "$db" >&2
    return 1
}

case "$mode" in
    up)
        podman build --layers --tag "$image" .
        if ! podman pod exists "$pod"; then
            podman pod create --name "$pod" --publish "127.0.0.1:$port:8080"
        fi
        if podman container exists "$db"; then
            podman start "$db"
        else
            export POSTGRES_PASSWORD="$DB_PASSWORD"
            podman run --detach --name "$db" --pod "$pod" \
                --env POSTGRES_USER="$DB_USER" --env POSTGRES_PASSWORD --env POSTGRES_DB=marketplace \
                --volume marketplace-pg:/var/lib/postgresql/data \
                docker.io/library/postgres:17
        fi
        ready_db
        podman run --detach --replace --name "$api" --pod "$pod" "${envs[@]}" "$image"
        for ((i=0; i<60; i++)); do
            code=$(curl --max-time 2 -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/products" || true)
            if [ "$code" = 401 ]; then
                printf 'API: http://localhost:%s\n' "$port"
                exit 0
            fi
            sleep 1
        done
        podman logs "$api" >&2
        exit 1
        ;;
    seed)
        ready_db
        podman run --rm --pod "$pod" "${envs[@]}" \
            --env SELLER_EMAIL --env SELLER_PASSWORD --env ADMIN_EMAIL --env ADMIN_PASSWORD \
            "$image" --spring.profiles.active=seed --spring.main.web-application-type=none
        ;;
    test)
        ready_db
        exists=$(podman exec "$db" psql -U "$DB_USER" -d marketplace -Atqc "SELECT 1 FROM pg_database WHERE datname = 'marketplace_test'")
        if [ "$exists" != 1 ]; then
            podman exec "$db" createdb -U "$DB_USER" marketplace_test
        fi
        podman build --layers --target build --tag localhost/marketplace-api-build .
        podman run --rm --pod "$pod" --env DB_USER --env DB_PASSWORD --env TEST_DB_URL \
            localhost/marketplace-api-build ./gradlew test --no-daemon --max-workers=2
        ;;
esac
