#!/usr/bin/env bash
# Generates a throwaway CA + server + client certificate chain for local Postgres
# client-cert (mTLS) auth via docker-compose. Output goes to ./certs, which is
# gitignored — never commit anything this script produces.
#
# Postgres maps the client certificate's CN directly to the role it authenticates
# as, and (with sslmode=verify-full) the app verifies the server certificate's CN
# against the hostname it connects to. Both are therefore fixed to match this
# repo's docker-compose setup:
#   - server cert CN = postgres            (the compose service name/hostname)
#   - client cert CN = $POSTGRES_USER      (defaults to "lifeos"; override via env
#                                            if POSTGRES_USER is ever changed)
#
# Re-run any time to rotate the certs; it always regenerates from scratch.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERTS_DIR="${SCRIPT_DIR}/../certs"
POSTGRES_USER="${POSTGRES_USER:-lifeos}"
DAYS_CA=3650
DAYS_LEAF=825

rm -rf "$CERTS_DIR"
mkdir -p "$CERTS_DIR"
cd "$CERTS_DIR"

echo "==> Generating CA"
openssl genrsa -out ca.key 4096
openssl req -x509 -new -nodes -key ca.key -sha256 -days "$DAYS_CA" \
  -subj "/CN=LifeOS Dev CA" -out ca.crt

echo "==> Generating server certificate (CN=postgres)"
openssl genrsa -out server.key 2048
openssl req -new -key server.key -subj "/CN=postgres" -out server.csr
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days "$DAYS_LEAF" -sha256 -out server.crt \
  -extfile <(printf "subjectAltName=DNS:postgres,DNS:localhost")
rm -f server.csr

echo "==> Generating client certificate (CN=${POSTGRES_USER})"
openssl genrsa -out client.key 2048
openssl req -new -key client.key -subj "/CN=${POSTGRES_USER}" -out client.csr
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days "$DAYS_LEAF" -sha256 -out client.crt
rm -f client.csr

echo "==> Converting client key to PKCS#8 DER (pgjdbc's expected sslkey format)"
openssl pkcs8 -topk8 -inform PEM -outform DER -in client.key -out client.pk8 -nocrypt

# Postgres refuses to start if ssl_key_file is group/world-readable. The
# server.key here still gets copied+chowned to the postgres uid at container
# start (see docker-compose.yml) since a host-owned bind mount can't satisfy
# that check on its own — this chmod is a baseline, not sufficient by itself.
chmod 600 ca.key server.key client.key client.pk8
chmod 644 ca.crt server.crt client.crt

rm -f ca.srl
echo "==> Done. Certs written to ${CERTS_DIR}"
