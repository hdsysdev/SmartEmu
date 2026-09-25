#!/usr/bin/env bash
# Generates the SmartEmu test PKI: a self-signed test CSCA and a Document Signer certificate it issues.
# The app signs EF.SOD with the Document Signer, so regenerating changes the trust anchor every other
# test environment uses. Existing files are kept unless FORCE=1 is set.
set -euo pipefail

cd "$(dirname "$0")"

CSCA=csca/smartemu-test-csca
DS=document-signer/smartemu-test-ds
P12_PASSWORD=smartemu

if [[ -e "$CSCA.key.pem" && "${FORCE:-0}" != 1 ]]; then
  echo "Test PKI already exists; run with FORCE=1 to replace it" >&2
  exit 1
fi

# CSCA: 20 years
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:prime256v1 -out "$CSCA.key.pem"
openssl req -new -x509 -config openssl.cnf -extensions csca_ext \
  -key "$CSCA.key.pem" -sha256 -days 7305 -set_serial "0x$(openssl rand -hex 16)" \
  -subj "/C=UT/O=SmartEmu/OU=Test PKI/CN=SmartEmu Test CSCA" \
  -out "$CSCA.cert.pem"

# Document Signer: 10 years, within the CSCA validity
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:prime256v1 -out "$DS.key.pem"
openssl req -new -config openssl.cnf \
  -key "$DS.key.pem" \
  -subj "/C=UT/O=SmartEmu/OU=Test PKI/CN=SmartEmu Test Document Signer" \
  -out "$DS.csr.pem"
openssl x509 -req -in "$DS.csr.pem" -extfile openssl.cnf -extensions ds_ext \
  -CA "$CSCA.cert.pem" -CAkey "$CSCA.key.pem" -set_serial "0x$(openssl rand -hex 16)" \
  -sha256 -days 3652 -out "$DS.cert.pem"
rm "$DS.csr.pem"

# DER copies and a PKCS#12 bundle (key, certificate and chain) for tools that want them
openssl x509 -in "$CSCA.cert.pem" -outform DER -out "$CSCA.cert.der"
openssl x509 -in "$DS.cert.pem" -outform DER -out "$DS.cert.der"
openssl pkcs12 -export -name "smartemu-test-ds" \
  -inkey "$DS.key.pem" -in "$DS.cert.pem" -certfile "$CSCA.cert.pem" \
  -passout "pass:$P12_PASSWORD" -out "$DS.p12"

openssl verify -CAfile "$CSCA.cert.pem" "$DS.cert.pem"
