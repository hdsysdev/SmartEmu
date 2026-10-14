#!/usr/bin/env bash
# Generates the PassportEmu test PKI: a self-signed test CSCA, the Document Signers it issues, and the flawed
# Document Signers the app's chip faults sign with; and for each country with a chip profile, a test CSCA of that
# country and the Document Signers it issues, with the key types the profile has. The app signs EF.SOD with a
# Document Signer, so regenerating a CSCA changes the trust anchor every other test environment uses.
#
# Legacy subjects retain SmartEmu; new country certificates say PassportEmu and TEST.
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
EC_BRAINPOOL_P384=(-algorithm EC -pkeyopt ec_paramgen_curve:brainpoolP384r1)
EC_BRAINPOOL_P512=(-algorithm EC -pkeyopt ec_paramgen_curve:brainpoolP512r1)
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

# add_days <YYYYmmddHHMMSSZ> <days>: that time, that many days later, in the same format
add_days() {
  date -j -u -v+"$2"d -f "%Y%m%d%H%M%SZ" "$1" "+%Y%m%d%H%M%SZ" 2>/dev/null \
    || date -u -d "$(echo "$1" | sed -E 's/(....)(..)(..)(..)(..)(..)Z/\1-\2-\3 \4:\5:\6/') UTC + $2 days" "+%Y%m%d%H%M%SZ"
}

# new_csca <path> <common name> [subject prefix] [genpkey key type options]: a self-signed CSCA, 20 years, starting
# today, or at CSCA_START (YYYYmmddHHMMSSZ) if that is set
new_csca() {
  local path=$1 name=$2 prefix=${3:-$SUBJECT_PREFIX}
  shift $(( $# < 3 ? $# : 3 ))
  local validity=(-days 7305)
  if [[ -n "${CSCA_START:-}" ]]; then
    validity=(-not_before "$CSCA_START" -not_after "$(add_days "$CSCA_START" 7305)")
  fi
  new_key "$path.key.pem" "$@"
  openssl req -new -x509 -config openssl.cnf -extensions csca_ext \
    -key "$path.key.pem" -sha256 "${validity[@]}" -set_serial "0x$(openssl rand -hex 16)" \
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

# csca_validity <CSCA path>: -not_before and -not_after options for a 3652 day Document Signer that starts when the
# CSCA does (-days counts from today, so it cannot be combined with -not_before)
csca_validity() {
  local start not_before
  start=$(openssl x509 -in "$1.cert.pem" -noout -startdate | cut -d= -f2)
  not_before=$(date -j -u -f "%b %e %H:%M:%S %Y %Z" "$start" "+%Y%m%d%H%M%SZ" 2>/dev/null \
    || date -u -d "$start" "+%Y%m%d%H%M%SZ")
  echo "-not_before $not_before -not_after $(add_days "$not_before" 3652)"
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

# country_pki <country> <CSCA key type array name> then triples of <Document Signer name suffix> <extensions>
# <key type array name>: a country's test CSCA and every Document Signer it issues, 10 years each. Call it once per
# country: a second call would not recreate the CSCA without FORCE, but with FORCE=1 it would replace it and orphan the
# signers issued by the first call.
# The -rsa and -p384 signers (US, AU, DE, FR) were added after the country's first signer; they start when the CSCA
# does, like the first signers, instead of on the day they were generated. The same goes for every signer of the
# countries in NEW_COUNTRIES, whose CSCAs start at NEW_CSCA_START instead of on the day they were generated, so that a
# reader whose clock is a little behind still accepts them. <country> is the code the names carry; Indonesia's IDN
# keeps its signer apart from the generic ID document signer, smartemu-test-ds-id, and its certificates say C=ID.
NEW_CSCA_START=20260930224757Z
NEW_COUNTRIES=" AT FI SG TW PT IDN HK MY NZ RU PH CN "
country_pki() {
  local country=$1 csca_key=$2[@]
  local lower
  lower=$(echo "$country" | tr '[:upper:]' '[:lower:]')
  local csca=csca/smartemu-test-csca-$lower
  local brand=PassportEmu
  case "$country" in DE|NL|GB|US) brand=SmartEmu ;; esac
  local iso=$country csca_start=
  case "$country" in IDN) iso=ID ;; esac
  case "$NEW_COUNTRIES" in *" $country "*) csca_start=$NEW_CSCA_START ;; esac
  local prefix="/C=$iso/O=$brand/OU=Test PKI"
  local csca_label="$brand TEST CSCA $country"
  if [[ "$brand" == SmartEmu ]]; then csca_label="SmartEmu Test CSCA $country"; fi
  shift 2
  if wanted "$csca.key.pem"; then
    CSCA_START=$csca_start new_csca "$csca" "$csca_label" "$prefix" "${!csca_key}"
  fi
  while [[ $# -ge 3 ]]; do
    local suffix=$1 extensions=$2 key=$3[@]
    shift 3
    local ds=document-signer/smartemu-test-ds-$lower$suffix
    if wanted "$ds.key.pem"; then
      local label="$brand TEST Document Signer $country"
      case "$extensions" in
        ds_ext) ;;
        *) label="$label ID documents" ;;
      esac
      if [[ "$suffix" == "-rsa" ]]; then label="$label RSA"; fi
      if [[ "$suffix" == "-p384" ]]; then label="$label P-384"; fi
      local validity=(-days 3652)
      case "$lower$suffix" in
        us-rsa|au-rsa|de-p384|fr-rsa) read -r -a validity <<< "$(csca_validity "$csca")" ;;
      esac
      if [[ -n "$csca_start" ]]; then read -r -a validity <<< "$(csca_validity "$csca")"; fi
      DS_PREFIX=$prefix
      DS_KEY=("${!key}")
      new_ds "$ds" "$label" "$extensions" "$csca" "${validity[@]}"
      unset DS_PREFIX DS_KEY
    fi
    openssl verify -CAfile "$csca.cert.pem" "$ds.cert.pem"
  done
}

# Emulator choices, not issuer facts: see docs/chip-profile-evidence.md. CSCA keys do not establish DS algorithms.
# The -rsa signers are SHA256withRSA (PKCS#1 v1.5) on RSA 2048; -p384 is SHA384withECDSA on brainpoolP384r1. They chain
# to the country CSCA, whose key type does not limit the DS key type (EC P-256 / brainpoolP256r1 here).
country_pki DE EC_BRAINPOOL_P256 "" ds_ext EC_BRAINPOOL_P256 -id ds_id_ext EC_BRAINPOOL_P256 -p384 ds_ext EC_BRAINPOOL_P384
country_pki NL EC_P256 "" ds_ext EC_P256 -id ds_nl_id_ext EC_P256 -rsa ds_ext RSA_2048
country_pki GB EC_P256 "" ds_ext EC_P256
country_pki US EC_P256 "" ds_ext EC_P256 -rsa ds_ext RSA_2048

# Wider passport profiles. EC P-256 CSCAs are test trust anchors, including for RSA DS certificates.
# The Belgian study explicitly names its DS key; Canada uses an RSA stand-in with different SOD padding.
country_pki FR EC_P256 "" ds_ext EC_P256 -rsa ds_ext RSA_2048
country_pki BE EC_P256 "" ds_ext RSA_2048
country_pki ES EC_P256 "" ds_ext EC_P256
country_pki IT EC_P256 "" ds_ext EC_P256
country_pki CH EC_P256 "" ds_ext EC_P256
country_pki CA EC_P256 "" ds_ext RSA_2048
country_pki AU EC_P256 "" ds_ext EC_P256 -rsa ds_ext RSA_2048

# More passport and ID profiles, from the Document Signer key types of real EF.SODs (the gmrtd repository's samples),
# except the stand-ins: Finland's passport signer, Taiwan, Portugal and Hong Kong, whose real signer key is unknown.
# The SOD signature of the Russian sample is ECDSA with a SHA-1 digest. RSA-PSS and China's id-data content type are
# not something the app's SOD generator makes, so those profiles sign PKCS#1 v1.5 / ldsSecurityObject instead.
country_pki AT EC_P256 "" ds_ext EC_BRAINPOOL_P256
country_pki FI EC_P256 "" ds_ext EC_P256 -id ds_id_ext EC_BRAINPOOL_P512
country_pki SG EC_P256 "" ds_ext RSA_2048
country_pki TW EC_P256 "" ds_ext EC_P256
country_pki PT EC_P256 "" ds_ext EC_P256
country_pki IDN EC_P256 "" ds_ext RSA_2048
country_pki HK EC_P256 "" ds_ext EC_P256
country_pki MY EC_P256 "" ds_ext RSA_2048
country_pki NZ EC_P256 "" ds_ext RSA_2048
country_pki RU EC_P256 "" ds_ext EC_P256
country_pki PH EC_P256 "" ds_ext RSA_2048
country_pki CN EC_P256 "" ds_ext RSA_2048

openssl verify -CAfile "$CSCA.cert.pem" "$DS.cert.pem" "$DS_ID.cert.pem"
openssl verify -CAfile "$UNTRUSTED_CSCA.cert.pem" "$DS_UNTRUSTED.cert.pem"
