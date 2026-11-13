#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

cd "$(dirname "$0")/.."

# Require the flake's tools so this checks the development shell, not host tools.
for tool in java node initdb pg_ctl psql; do
  case "$(command -v "$tool")" in
    /nix/store/*) ;;
    *) printf 'Run this script with nix develop --command bash %s\n' "$0" >&2; exit 1 ;;
  esac
done
case "${JAVA_HOME:-}" in /nix/store/*) ;; *) echo 'JAVA_HOME must point to the Nix JDK.' >&2; exit 1 ;; esac
java -version
node --version
psql --version

# Use an absolute system temporary directory, never a caller-supplied repository path.
state_dir=$(mktemp -d /tmp/libertywiki-verify.XXXXXXXX)
chmod 700 "$state_dir"
pg_started=false
app_pid=
cleanup() {
  status=$?
  trap - EXIT INT TERM
  if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
    kill "$app_pid" 2>/dev/null || true
    for ((i=0; i<10; i++)); do
      kill -0 "$app_pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
  fi
  if [[ "$pg_started" == true ]]; then
    if ! pg_ctl -D "$state_dir/postgres" status >/dev/null 2>&1; then
      : # It may have failed before startup.
    elif ! pg_ctl -D "$state_dir/postgres" -m immediate -t 30 -w stop >/dev/null; then
      echo "Could not stop the temporary PostgreSQL instance; retained $state_dir." >&2
      exit 1
    fi
  fi
  rm -f -- "$state_dir/db-password"
  if [[ "$status" -eq 0 ]]; then
    rm -rf -- "$state_dir"
  else
    echo "Verification failed; temporary diagnostics retained in $state_dir (owner access only)." >&2
    for log in "$state_dir/postgres.log" "$state_dir/app.log"; do
      if [[ -f "$log" ]]; then
        printf '\nLast lines of %s:\n' "$log" >&2
        tail -n 80 "$log" | node -e '
          let input = "";
          process.stdin.setEncoding("utf8");
          process.stdin.on("data", chunk => input += chunk);
          process.stdin.on("end", () => {
            for (const name of ["PGPASSWORD", "WIKI_HTTP_OPERATOR_TOKEN"]) {
              const secret = process.env[name];
              if (secret) input = input.split(secret).join("[REDACTED]");
            }
            process.stderr.write(input);
          });' || true
      fi
    done
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

free_port() {
  node -e 'const s = require("node:net").createServer(); s.listen(0, "127.0.0.1", () => { console.log(s.address().port); s.close(); }); s.on("error", () => process.exit(1));'
}
export PGHOST=127.0.0.1 PGPORT
PGPORT=$(free_port)
export PGUSER=libertywiki
export PGPASSWORD
PGPASSWORD=$(node -e 'console.log(require("node:crypto").randomBytes(32).toString("hex"))')
printf '%s\n' "$PGPASSWORD" > "$state_dir/db-password"
chmod 600 "$state_dir/db-password"
initdb -D "$state_dir/postgres" --username="$PGUSER" --encoding=UTF8 --locale=C \
  --auth-local=trust --auth-host=scram-sha-256 --pwfile="$state_dir/db-password" >/dev/null
rm -f -- "$state_dir/db-password"
# Startup fails if another process takes the selected port; never connect to it.
pg_started=true
pg_ctl -D "$state_dir/postgres" -l "$state_dir/postgres.log" \
  -o "-h 127.0.0.1 -p $PGPORT -k $state_dir" -w start >/dev/null
createdb libertywiki
createdb libertywiki_test
export WIKI_DB_URL="jdbc:postgresql://127.0.0.1:$PGPORT/libertywiki"
export WIKI_DB_USER="$PGUSER" WIKI_DB_PASSWORD="$PGPASSWORD"
export WIKI_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$PGPORT/libertywiki_test"
export WIKI_TEST_DB_USER="$PGUSER" WIKI_TEST_DB_PASSWORD="$PGPASSWORD"

npm ci --prefix web
npm run build --prefix web
./gradlew test check postgresTest bootJar
if [[ -n "${WIKI_MW_BASE_URL:-}" ]]; then
  ./gradlew mediaWikiTest
else
  echo 'MediaWiki differential tests skipped: WIKI_MW_BASE_URL is not set.'
fi

# Ask Gradle for the executable archive, avoiding stale or plain jar files.
jar_path=$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print)
if [[ -z "$jar_path" || "$jar_path" == *$'\n'* ]]; then
  echo 'Expected exactly one bootJar in build/libs; remove stale build outputs and retry.' >&2
  exit 1
fi
app_port=$(free_port)
export WIKI_BIND_ADDRESS=127.0.0.1
export WIKI_ANONYMOUS_READ=true WIKI_ANONYMOUS_EDIT=false WIKI_ANONYMOUS_CREATE=false
export WIKI_HTTP_OPERATOR_TOKEN
WIKI_HTTP_OPERATOR_TOKEN=$(node -e 'console.log(require("node:crypto").randomBytes(32).toString("hex"))')
export WIKI_BROWSER_URL="http://127.0.0.1:$app_port" WIKI_BROWSER_TOKEN="$WIKI_HTTP_OPERATOR_TOKEN"
java -jar "$jar_path" --server.port="$app_port" >"$state_dir/app.log" 2>&1 &
app_pid=$!
ready=false
for ((attempt=0; attempt<120; attempt++)); do
  if ! kill -0 "$app_pid" 2>/dev/null; then
    echo 'Application exited before becoming ready.' >&2
    exit 1
  fi
  if curl --silent --fail --max-time 1 "$WIKI_BROWSER_URL/" >/dev/null; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo 'Application did not become ready within 120 attempts.' >&2
  exit 1
fi
npm test --prefix web
