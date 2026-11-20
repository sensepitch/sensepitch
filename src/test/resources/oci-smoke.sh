set -euo pipefail
smoke_dir=$(mktemp -d /tmp/sensepitch-oci-smoke.XXXXXX)
cat > "$smoke_dir/config.yml" <<'EOF'
listen:
  address: 0.0.0.0
  httpsPort: 17443
  ssl:
    keyPath: /etc/sensepitch/config/test.key
    certPath: /etc/sensepitch/config/test.crt
admin:
  address: 0.0.0.0
  port: 9401
metrics:
  enable: true
  prometheus:
    port: 9400
# Keep readiness observable even if the test is descheduled during shutdown.
shutdownTimeoutMillis: 25000
shutdownAcceptGraceMillis: 12000
protection:
  disable: true
sites:
  localhost:
    response:
      text: "OCI smoke OK"
#END
EOF
chmod 644 "$smoke_dir/config.yml"
# The runtime image omits test resources, so mount the test TLS pair as files.
cp src/test/resources/ssl/test.key src/test/resources/ssl/test.crt "$smoke_dir/"
chmod 644 "$smoke_dir/test.key" "$smoke_dir/test.crt"
container=
cleanup() {
  result=$?
  if [ -n "$container" ]; then
    if [ "$result" -ne 0 ]; then docker logs "$container" >&2 || true; fi
    docker rm -f "$container" >/dev/null || true
  fi
  rm -f "$smoke_dir/config.yml" "$smoke_dir/test.crt" "$smoke_dir/test.key" "$smoke_dir/metrics.out"
  rmdir "$smoke_dir"
}
trap cleanup EXIT
chmod 755 "$smoke_dir"
container=$(docker run -d --pull=never --read-only --tmpfs /tmp:rw,exec \
  -p 127.0.0.1::17443 -p 127.0.0.1::9401 -p 127.0.0.1::9400 \
  --mount "type=bind,src=$smoke_dir,dst=/etc/sensepitch/config,readonly" "$1")
admin=$(docker port "$container" 9401/tcp)
https=$(docker port "$container" 17443/tcp)
metrics=$(docker port "$container" 9400/tcp)
for attempt in $(seq 1 40); do
  if curl -fs --max-time 1 "http://$admin/readyz" >/dev/null && curl -kfs --max-time 1 -H 'Host: localhost' "https://$https/" >/dev/null; then break; fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$container")" != true ]; then exit 1; fi
  sleep 0.5
done
for endpoint in healthz readyz; do
  curl -fsS --max-time 5 -o /dev/null -w "$endpoint HTTP %{http_code}\n" "http://$admin/$endpoint"
done
body=$(curl -kfSs --max-time 5 -H 'Host: localhost' "https://$https/")
test "$body" = "OCI smoke OK"
printf 'HTTPS body: %s\n' "$body"
curl -fsS --max-time 5 "http://$metrics/metrics" > "$smoke_dir/metrics.out"
grep -F 'sensepitch_edge_server_live{ingress="localhost"} 1' "$smoke_dir/metrics.out"
docker exec "$container" sh -ec 'test "$(cat /proc/1/comm)" = java; test "$(id -u):$(id -g)" = 65532:65532; printf "PID 1: java, UID:GID %s:%s\n" "$(id -u)" "$(id -g)"'
docker kill --signal SIGTERM "$container" >/dev/null
ready_status=
for attempt in $(seq 1 200); do
  ready_status=$(curl -s --max-time 1 -o /dev/null -w '%{http_code}' "http://$admin/readyz" || true)
  if [ "$ready_status" = 503 ]; then break; fi
  sleep 0.05
done
test "$ready_status" = 503
printf 'Readiness after SIGTERM: HTTP %s\n' "$ready_status"
exit_code=$(timeout 30 docker wait "$container")
case "$exit_code" in 0|143) ;; *) exit 1 ;; esac
test "$(docker inspect -f '{{.State.OOMKilled}}' "$container")" = false
printf 'Container exit: %s\n' "$exit_code"
