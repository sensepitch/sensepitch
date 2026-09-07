#!/bin/bash
#
# A/B load test of the JA4 TLS fingerprint: the same proxy, once with
# SENSEPITCH_EDGE_JA4_ENABLE=false and once with true.
#
# Two things make this different from start.sh:
#
# 1. No upstream. The site is configured with a response (see ResponseConfig), so the proxy answers
#    from an in-process channel. Nothing is measured but TLS termination, HTTP parsing and the
#    handlers. Without this the upstream round trip dominates and the JA4 delta disappears in it.
#
# 2. No keep alive. Ja4Handler removes itself from the pipeline after the ClientHello, so the cost
#    is paid once per connection. With keep alive on, a run of 100k requests would pay it a few
#    hundred times and report no difference regardless of the implementation.
#
# The RSA handshake is still far more expensive than the fingerprint, so expect the difference to
# be small. Ja4Benchmark measures the fingerprint on its own and gives the absolute number.
#
# Requires: vegeta, openssl, a built ../target/sensepitch-edge-1.0-SNAPSHOT-with-dependencies.jar

set -e
cd "$(dirname "$0")"

JAR=../target/sensepitch-edge-1.0-SNAPSHOT-with-dependencies.jar
PORT=${PORT:-17444}
DURATION=${DURATION:-10s}
# above what the hardware can serve with a handshake per request, to reach saturation
RATE=${RATE:-5000}
URL=https://localhost:$PORT/

if [ ! -f "$JAR" ]; then
  echo "build first: ./mvnw package -DskipTests" >&2
  exit 1
fi

mkdir -p ssl
if [ ! -f ssl/nginx.crt ]; then
  openssl req -x509 -nodes -days 365 \
    -newkey rsa:2048 \
    -keyout ssl/nginx.key \
    -out ssl/nginx.crt \
    -subj "/CN=localhost"
fi

# everything except JA4 is held constant between the two runs
export SENSEPITCH_EDGE_LISTEN_HTTPS_PORT=$PORT
export SENSEPITCH_EDGE_LISTEN_SSL_KEY_PATH=ssl/nginx.key
export SENSEPITCH_EDGE_LISTEN_SSL_CERT_PATH=ssl/nginx.crt
export SENSEPITCH_EDGE_SITES_0_KEY=localhost
export SENSEPITCH_EDGE_SITES_0_RESPONSE_TEXT=ok
export SENSEPITCH_EDGE_PROTECTION_DISABLE=true
export SENSEPITCH_EDGE_METRICS_ENABLE=false

waitForPort() {
  for _ in $(seq 1 50); do
    if wget -q -O /dev/null --no-check-certificate "$URL"; then
      return 0
    fi
    sleep 0.2
  done
  echo "proxy did not come up, see $LOG" >&2
  exit 1
}

# $1: value of SENSEPITCH_EDGE_JA4_ENABLE
run() {
  export SENSEPITCH_EDGE_JA4_ENABLE=$1
  LOG=ja4-benchmark-$1.log
  java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -jar $JAR > "$LOG" 2>&1 &
  PID=$!
  trap 'kill $PID 2>/dev/null' EXIT
  waitForPort

  echo
  echo "=== ja4 enable=$1 ==="
  # first run is discarded, it warms up the JIT
  echo "GET $URL" | vegeta attack -insecure -duration=$DURATION -timeout=10s \
    -rate=$RATE -keepalive=false > /dev/null
  echo "GET $URL" | vegeta attack -insecure -duration=$DURATION -timeout=10s \
    -rate=$RATE -keepalive=false | vegeta report

  kill $PID
  wait $PID 2>/dev/null || true
  trap - EXIT
}

run false
run true

echo
echo "Compare the throughput and the p99 latency of the two runs."
echo "For the per connection cost of the fingerprint on its own, run Ja4Benchmark."
