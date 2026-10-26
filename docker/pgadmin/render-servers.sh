#!/usr/bin/env bash
set -euo pipefail

template="/templates/pgadmin-servers.json"
output="/output/servers.json"

umask 022
temporary="$(mktemp /output/servers.XXXXXX)"
trap 'rm -f "$temporary"' EXIT

jq -e '
  walk(
    if type == "string" then
      gsub(
        "\\$\\{(?<key>[A-Z0-9_]+)\\}";
        env[.key] // error("Missing variable: " + .key)
      )
    else
      .
    end
  )
  | .Servers |= with_entries(.value.Port |= tonumber)
' "$template" > "$temporary"

mv "$temporary" "$output"

echo "pgAdmin: servers.json generated"
