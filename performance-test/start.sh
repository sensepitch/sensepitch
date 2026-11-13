#!/bin/bash
#
# Compares sensepitch-edge with nginx as TLS terminating reverse proxy in front of the same
# static nginx upstream (see docker-compose.yml). Leaves the containers and the proxy running,
# so stress.py or further vegeta runs (e.g. with requests.txt) can be pointed at them.
#
# Requires: docker compose, vegeta, openssl, wget, a built
# ../target/sensepitch-edge-*-with-dependencies.jar

set -e
cd "$(dirname "$0")"

for cmd in java vegeta docker openssl wget; do
  if ! command -v $cmd > /dev/null; then
    echo "$cmd not found in PATH" >&2
    exit 1
  fi
done

# newest fat JAR, whatever the project version
JAR=$(ls -t ../target/sensepitch-edge-*-with-dependencies.jar 2>/dev/null | head -n 1)
if [ ! -f "$JAR" ]; then
  echo "build first: ./mvnw package -DskipTests" >&2
  exit 1
fi

printContainerIp() {
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
    "$(docker compose ps -q $1)"
}

# must exist before docker compose up, otherwise docker creates directories in their place
mkdir -p ssl html
if [ ! -f ssl/nginx.crt ]; then
  openssl req -x509 -nodes -days 365 \
    -newkey rsa:2048 \
    -keyout ssl/nginx.key \
    -out ssl/nginx.crt \
    -subj "/CN=localhost"
fi

dd if=/dev/zero bs=1024 count=1024 of=html/1mb.img
dd if=/dev/zero bs=1024 count=100 of=html/100kb.img
dd if=/dev/zero bs=1024 count=10 of=html/10kb.img

docker compose up -d

UPSTREAM_IP=$(printContainerIp nginx-static)
UPSTREAM_PORT=80
NGINX_PROXY_IP=$(printContainerIp nginx-proxy)
NGINX_PROXY_PORT=443

chmod u+x ./wait-for-it.sh
./wait-for-it.sh $UPSTREAM_IP:$UPSTREAM_PORT --timeout=10
./wait-for-it.sh $NGINX_PROXY_IP:$NGINX_PROXY_PORT --timeout=10

# listen.address has no effective default once any other listen setting is present
SENSEPITCH_EDGE_LISTEN_ADDRESS=0.0.0.0
SENSEPITCH_EDGE_LISTEN_HTTPS_PORT=17443
SENSEPITCH_EDGE_LISTEN_SSL_KEY_PATH=ssl/nginx.key
SENSEPITCH_EDGE_LISTEN_SSL_CERT_PATH=ssl/nginx.crt
SENSEPITCH_EDGE_SITES_0_KEY=localhost
SENSEPITCH_EDGE_SITES_0_UPSTREAM_TARGET=$UPSTREAM_IP:$UPSTREAM_PORT
SENSEPITCH_EDGE_METRICS_ENABLE=true
# SENSEPITCH_EDGE_PROTECTION_DISABLE=true
SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_PREFIX=x
SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_SECRET=variousboxes
# Protection stays on, but the benchmark URIs bypass the challenge, otherwise every request
# is answered with the challenge page instead of reaching the upstream.
SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_BYPASS_URIS_0=/10kb.img
SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_BYPASS_URIS_1=/100kb.img
SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_BYPASS_URIS_2=/1mb.img
# requests.txt, to measure the upstream 404 path
SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_BYPASS_URIS_3=/NOT_FOUND

export ${!SENSEPITCH_@}

PROXY_URL=https://localhost:$SENSEPITCH_EDGE_LISTEN_HTTPS_PORT

java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -jar "$JAR" > sensepitch-edge.log 2>&1 &
PROXY_PID=$!
./wait-for-it.sh localhost:$SENSEPITCH_EDGE_LISTEN_HTTPS_PORT --timeout=30

wget -q -O /dev/null --no-check-certificate $PROXY_URL/10kb.img

TEST_DURATION=${TEST_DURATION:-10s}
TEST_RATE=${TEST_RATE:-500}

echo
echo "=== nginx ==="
echo "GET https://$NGINX_PROXY_IP:$NGINX_PROXY_PORT/10kb.img" | vegeta attack -insecure -duration=$TEST_DURATION -timeout=10s -rate=$TEST_RATE -keepalive=true | vegeta report
echo
echo "=== sensepitch-edge ==="
echo "GET $PROXY_URL/10kb.img" | vegeta attack -insecure -duration=$TEST_DURATION -timeout=10s -rate=$TEST_RATE -keepalive=true | vegeta report

echo
echo "sensepitch-edge is still running, PID $PROXY_PID, log in sensepitch-edge.log."
echo "Stop it with: kill $PROXY_PID; docker compose down"
