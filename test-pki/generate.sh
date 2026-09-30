#!/usr/bin/env bash
# Generates the SmartEmu test PKI: a self-signed test CSCA, the Document Signers it issues, and the flawed
# Document Signers the app's chip faults sign with; and for each country with a chip profile, a test CSCA of that
# country and the Document Signers it issues, with the key types the profile has. The app signs EF.SOD with a
# Document Signer, so regenerating a CSCA changes the trust anchor every other test environment uses.
#
# Every certificate says SmartEmu and Test in its subject, so none can pass for a real issuer's.
#
# Files that exist are kept, so running it again only adds what's missing; FORCE=1 replaces everything.
# Needs OpenSSL 3.4 or later, for -not_before and -not_after.
set -euo pipefail

cd "$(dirname "$0")"

CSCA=csca/smartemu-test-csca
DS=document-signer/smartemu-test-ds
DS_ID=document-signer/smartemu-test-ds-id
DS_EXPIRED=document-signer/smartemu-test-ds-expired
UNTRUSTED_CSCA=untrusted-csca/smartemu-unknown-csca
DS_UNTRUSTED=document-signer/smartemu-test-ds-untrusted
P12_PASSWORD=smartemu
SUBJECT_PREFIX="/C=UT/O=SmartEmu/OU=Test PKI"

# Key types, as genpkey options
EC_P256=(-algorithm EC -pkeyopt ec_paramgen_curve:prime256v1)
EC_BRAINPOOL_P256=(-algorithm EC -pkeyopt ec_paramgen_curve:brainpoolP256r1)
RSA_2048=(-algorithm RSA -pkeyopt rsa_keygen_bits:2048)

# Whether $1 needs generating: it doesn't exist, or FORCE=1
wanted() { [[ ! -e "$1" || "${FORCE:-0}" == 1 ]]; }

# new_key <path> [genpkey key type options]: P-256 unless told otherwise
new_key() {
  local path=$1
  shift
  if [[ $# -eq 0 ]]; then set -- "${EC_P256[@]}"; fi
  openssl genpkey "$@" -out "$path"
}

# new_csca <path> <common name> [subject prefix] [genpkey key type options]: a self-signed CSCA, 20 years
new_csca() {
  local path=$1 name=$2 prefix=${3:-$SUBJECT_PREFIX}
  shift $(( $# < 3 ? $# : 3 ))
  new_key "$path.key.pem" "$@"
  openssl req -new -x509 -config openssl.cnf -extensions csca_ext \
    -key "$path.key.pem" -sha256 -days 7305 -set_serial "0x$(openssl rand -hex 16)" \
    -subj "$prefix/CN=$name" \
    -out "$path.cert.pem"
  openssl x509 -in "$path.cert.pem" -outform DER -out "$path.cert.der"
}

# new_ds <path> <common name> <extensions> <issuing CSCA path> <validity options...>, in the UT test PKI with a P-256
# key, or with the subject prefix and key type options in DS_PREFIX and DS_KEY
new_ds() {
  local path=$1 name=$2 extensions=$3 csca=$4
  shift 4
  new_key "$path.key.pem" ${DS_KEY[@]+"${DS_KEY[@]}"}
  openssl req -new -config openssl.cnf -key "$path.key.pem" -subj "${DS_PREFIX:-$SUBJECT_PREFIX}/CN=$name" -out "$path.csr.pem"
  openssl x509 -req -in "$path.csr.pem" -extfile openssl.cnf -extensions "$extensions" \
    -CA "$csca.cert.pem" -CAkey "$csca.key.pem" -set_serial "0x$(openssl rand -hex 16)" \
    -sha256 "$@" -out "$path.cert.pem"
  rm "$path.csr.pem"
  openssl x509 -in "$path.cert.pem" -outform DER -out "$path.cert.der"
}

mkdir -p csca document-signer untrusted-csca

if wanted "$CSCA.key.pem"; then
  new_csca "$CSCA" "SmartEmu Test CSCA"
fi

# Document Signers for passports and for TD1 cards: 10 years, within the CSCA validity
if wanted "$DS.key.pem"; then
  new_ds "$DS" "SmartEmu Test Document Signer" ds_ext "$CSCA" -days 3652
  # A PKCS#12 bundle (key, certificate and chain) for tools that want one
  openssl pkcs12 -export -name "smartemu-test-ds" \
    -inkey "$DS.key.pem" -in "$DS.cert.pem" -certfile "$CSCA.cert.pem" \
    -passout "pass:$P12_PASSWORD" -out "$DS.p12"
fi
if wanted "$DS_ID.key.pem"; then
  new_ds "$DS_ID" "SmartEmu Test Document Signer for ID documents" ds_id_ext "$CSCA" -days 3652
fi

# Chip faults: a Document Signer that expired a day after the CSCA was issued, and one from a CSCA no one trusts
if wanted "$DS_EXPIRED.key.pem"; then
  not_before=$(openssl x509 -in "$CSCA.cert.pem" -noout -startdate | cut -d= -f2)
  not_before=$(date -j -u -f "%b %e %H:%M:%S %Y %Z" "$not_before" "+%Y%m%d%H%M%SZ" 2>/dev/null \
    || date -u -d "$not_before" "+%Y%m%d%H%M%SZ")
  not_after=$(date -j -u -v+1d -f "%Y%m%d%H%M%SZ" "$not_before" "+%Y%m%d%H%M%SZ" 2>/dev/null \
    || date -u -d "$(echo "$not_before" | sed -E 's/(....)(..)(..)(..)(..)(..)Z/\1-\2-\3 \4:\5:\6/') UTC + 1 day" "+%Y%m%d%H%M%SZ")
  new_ds "$DS_EXPIRED" "SmartEmu Test Document Signer (expired)" ds_fault_ext "$CSCA" \
    -not_before "$not_before" -not_after "$not_after"
fi
if wanted "$UNTRUSTED_CSCA.key.pem"; then
  new_csca "$UNTRUSTED_CSCA" "SmartEmu Unknown CSCA"
fi
if wanted "$DS_UNTRUSTED.key.pem"; then
  new_ds "$DS_UNTRUSTED" "SmartEmu Test Document Signer (untrusted)" ds_fault_ext "$UNTRUSTED_CSCA" -days 3652
fi

# country_pki <country> <CSCA key type array name> then pairs of <Document Signer name suffix> <extensions>
# <key type array name>: a country's test CSCA and the Document Signers it issues, 10 years each
country_pki() {
  local country=$1 csca_key=$2[@]
  local lower
  lower=$(echo "$country" | tr '[:upper:]' '[:lower:]')
  local csca=csca/smartemu-test-csca-$lower
  local prefix="/C=$country/O=SmartEmu/OU=Test PKI"
  shift 2
  if wanted "$csca.key.pem"; then
    new_csca "$csca" "SmartEmu Test CSCA $country" "$prefix" "${!csca_key}"
  fi
  while [[ $# -ge 3 ]]; do
    local suffix=$1 extensions=$2 key=$3[@]
    shift 3
    local ds=document-signer/smartemu-test-ds-$lower$suffix
    if wanted "$ds.key.pem"; then
      local label="SmartEmu TEST Document Signer $country"
      case "$extensions" in
        ds_ext) ;;
        *) label="$label ID documents" ;;
      esac
      if [[ "$suffix" == "-rsa" ]]; then label="$label RSA"; fi
      DS_PREFIX=$prefix
      DS_KEY=("${!key}")
      new_ds "$ds" "$label" "$extensions" "$csca" -days 3652
      unset DS_PREFIX DS_KEY
    fi
    openssl verify -CAfile "$csca.cert.pem" "$ds.cert.pem"
  done
}

# Germany signs with Brainpool keys, the Netherlands' first-generation passports with RSA
country_pki DE EC_BRAINPOOL_P256 "" ds_ext EC_BRAINPOOL_P256 -id ds_id_ext EC_BRAINPOOL_P256
country_pki NL EC_P256 "" ds_ext EC_P256 -id ds_nl_id_ext EC_P256 -rsa ds_ext RSA_2048
country_pki GB EC_P256 "" ds_ext EC_P256
country_pki US EC_P256 "" ds_ext EC_P256

openssl verify -CAfile "$CSCA.cert.pem" "$DS.cert.pem" "$DS_ID.cert.pem"
openssl verify -CAfile "$UNTRUSTED_CSCA.cert.pem" "$DS_UNTRUSTED.cert.pem"
