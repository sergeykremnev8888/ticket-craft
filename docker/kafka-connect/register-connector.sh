#!/usr/bin/env bash
set -euo pipefail

: "${CONNECTOR_CONFIG:?Set CONNECTOR_CONFIG}"
: "${DB_USERNAME:?Set DB_USERNAME}"
: "${DB_PASSWORD:?Set DB_PASSWORD}"
: "${DB_HOST:?Set DB_HOST}"
: "${DB_PORT:?Set DB_PORT}"
: "${DB_NAME:?Set DB_NAME}"

CONNECT_URL="${CONNECT_URL:-http://kafka-connect:8083}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-60}"

[[ "$MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || {
  echo "MAX_ATTEMPTS must be a positive integer" >&2
  exit 1
}

umask 077
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

payload="$workdir/config.json"
response="$workdir/response.json"

name="$(jq -er \
  '.name | select(type == "string" and length > 0)' \
  "$CONNECTOR_CONFIG")"

encoded_name="$(jq -nr --arg name "$name" '$name | @uri')"
connector_url="${CONNECT_URL%/}/connectors/$encoded_name"

# Подставляем credentials, сохраняя остальные поля JSON.
jq 'del(.name)
    | .["database.hostname"] = env.DB_HOST
    | .["database.port"] = env.DB_PORT
    | .["database.dbname"] = env.DB_NAME
    | .["database.user"] = env.DB_USERNAME
    | .["database.password"] = env.DB_PASSWORD' \
  "$CONNECTOR_CONFIG" > "$payload"

request() {
  local method="$1" url="$2" code
  local -a args=()

  if [[ "$method" == "PUT" ]]; then
    args+=(--data-binary "@$payload")
  fi

  if ! code="$(curl --silent --show-error \
      --connect-timeout 3 --max-time 5 \
      --request "$method" \
      --header 'Accept: application/json' \
      --header 'Content-Type: application/json' \
      --output "$response" --write-out '%{http_code}' \
      "${args[@]}" "$url")"; then
    return 1
  fi

  case "$code" in
    200|201)
      return 0
      ;;
    404|408|409|429|5??)
      return 1
      ;;
    *)
      # Ответ может содержать credentials — не выводим его.
      echo "$name: $method failed, HTTP $code" >&2
      exit 1
      ;;
  esac
}

echo "$name: registering connector"
registered=false

for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
  if request PUT "$connector_url/config"; then
    registered=true
    break
  fi
  sleep 2
done

if [[ "$registered" != true ]]; then
  echo "$name: registration attempts exhausted" >&2
  exit 1
fi

last_state=""

for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
  if request GET "$connector_url/status"; then
    state="$(jq -r \
      '"connector=\(.connector.state), tasks=\([.tasks[].state] | join(","))"' \
      "$response")"

    if [[ "$state" != "$last_state" ]]; then
      echo "$name: $state"
      last_state="$state"
    fi

    if jq -e '
        .connector.state == "RUNNING"
        and (.tasks | length > 0)
        and all(.tasks[]; .state == "RUNNING")
      ' "$response" > /dev/null; then
      echo "$name: registration completed"
      exit 0
    fi
  fi
  sleep 2
done

echo "$name: connector/tasks did not reach RUNNING; inspect Kafka Connect logs" >&2
exit 1
