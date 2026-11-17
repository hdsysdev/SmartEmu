#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Generates the PassportEmu test PKI: a self-signed test CSCA, the Document Signers it issues, and the flawed
# Document Signers the app's chip faults sign with; and for each country with a chip profile, a test CSCA of that
# country and the Document Signers it issues, with the key types the profile has. The app signs EF.SOD with a
# Document Signer, so regenerating a CSCA changes the trust anchor every test environment uses.
#
# Every certificate starts at PKI_START, not on the day it was generated, so that a reader whose clock is a little
# behind still accepts it: CSCAs run 7305 days (20 years) and Document Signers 3652 days (10 years) from then.
#
# Files that exist are kept, so running it again only adds what's missing; FORCE=1 replaces everything.
# Needs OpenSSL 3.4 or later, for -not_before and -not_after.
set -euo pipefail

cd "$(dirname "$0")"

PKI_START=20261116000000Z
CSCA=csca/passportemu-test-csca
DS=document-signer/passportemu-test-ds
DS_ID=document-signer/passportemu-test-ds-id
DS_EXPIRED=document-signer/passportemu-test-ds-expired
UNTRUSTED_CSCA=untrusted-csca/passportemu-unknown-csca
DS_UNTRUSTED=document-signer/passportemu-test-ds-untrusted
BRAND=PassportEmu
SUBJECT_PREFIX="/C=UT/O=$BRAND/OU=Test PKI"

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

# new_csca <path> <common name> [subject prefix] [genpkey key type options]: a self-signed CSCA, 20 years from PKI_START
new_csca() {
  local path=$1 name=$2 prefix=${3:-$SUBJECT_PREFIX}
  shift $(( $# < 3 ? $# : 3 ))
  new_key "$path.key.pem" "$@"
  openssl req -new -x509 -config openssl.cnf -extensions csca_ext \
    -key "$path.key.pem" -sha256 -not_before "$PKI_START" -not_after "$(add_days "$PKI_START" 7305)" \
    -set_serial "0x$(openssl rand -hex 16)" -subj "$prefix/CN=$name" \
    -out "$path.cert.pem"
  openssl x509 -in "$path.cert.pem" -outform DER -out "$path.cert.der"
}

# new_ds <path> <common name> <extensions> <issuing CSCA path> [days, 3652 by default], from PKI_START, in the UT test
# PKI with a P-256 key, or with the subject prefix and key type options in DS_PREFIX and DS_KEY
new_ds() {
  local path=$1 name=$2 extensions=$3 csca=$4 days=${5:-3652}
  new_key "$path.key.pem" ${DS_KEY[@]+"${DS_KEY[@]}"}
  openssl req -new -config openssl.cnf -key "$path.key.pem" -subj "${DS_PREFIX:-$SUBJECT_PREFIX}/CN=$name" -out "$path.csr.pem"
  openssl x509 -req -in "$path.csr.pem" -extfile openssl.cnf -extensions "$extensions" \
    -CA "$csca.cert.pem" -CAkey "$csca.key.pem" -set_serial "0x$(openssl rand -hex 16)" \
    -sha256 -not_before "$PKI_START" -not_after "$(add_days "$PKI_START" "$days")" -out "$path.cert.pem"
  rm "$path.csr.pem"
  openssl x509 -in "$path.cert.pem" -outform DER -out "$path.cert.der"
}

mkdir -p csca document-signer untrusted-csca

if wanted "$CSCA.key.pem"; then
  new_csca "$CSCA" "$BRAND Test CSCA"
fi

# Document Signers for passports and for TD1 cards
if wanted "$DS.key.pem"; then
  new_ds "$DS" "$BRAND Test Document Signer" ds_ext "$CSCA"
fi
if wanted "$DS_ID.key.pem"; then
  new_ds "$DS_ID" "$BRAND Test Document Signer for ID documents" ds_id_ext "$CSCA"
fi

# Chip faults: a Document Signer that expired a day after it started, and one from a CSCA no one trusts
if wanted "$DS_EXPIRED.key.pem"; then
  new_ds "$DS_EXPIRED" "$BRAND Test Document Signer (expired)" ds_fault_ext "$CSCA" 1
fi
if wanted "$UNTRUSTED_CSCA.key.pem"; then
  new_csca "$UNTRUSTED_CSCA" "$BRAND Unknown CSCA"
fi
if wanted "$DS_UNTRUSTED.key.pem"; then
  new_ds "$DS_UNTRUSTED" "$BRAND Test Document Signer (untrusted)" ds_fault_ext "$UNTRUSTED_CSCA"
fi

# country_pki <country> <CSCA key type array name> then triples of <Document Signer name suffix> <extensions>
# <key type array name>: a country's test CSCA and every Document Signer it issues. Call it once per country: a second
# call would not recreate the CSCA without FORCE, but with FORCE=1 it would replace it and orphan the signers issued
# by the first call. <country> is the code the names carry; Indonesia's IDN keeps its signer apart from the generic ID
# document signer, passportemu-test-ds-id, and its certificates say C=ID.
country_pki() {
  local country=$1 csca_key=$2[@]
  local lower
  lower=$(echo "$country" | tr '[:upper:]' '[:lower:]')
  local csca=csca/passportemu-test-csca-$lower
  local iso=$country
  case "$country" in IDN) iso=ID ;; esac
  local prefix="/C=$iso/O=$BRAND/OU=Test PKI"
  shift 2
  if wanted "$csca.key.pem"; then
    new_csca "$csca" "$BRAND Test CSCA $country" "$prefix" "${!csca_key}"
  fi
  while [[ $# -ge 3 ]]; do
    local suffix=$1 extensions=$2 key=$3[@]
    shift 3
    local ds=document-signer/passportemu-test-ds-$lower$suffix
    if wanted "$ds.key.pem"; then
      local label="$BRAND Test Document Signer $country"
      case "$extensions" in
        ds_ext) ;;
        *) label="$label ID documents" ;;
      esac
      if [[ "$suffix" == "-rsa" ]]; then label="$label RSA"; fi
      if [[ "$suffix" == "-p384" ]]; then label="$label P-384"; fi
      DS_PREFIX=$prefix
      DS_KEY=("${!key}")
      new_ds "$ds" "$label" "$extensions" "$csca"
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
# Kosovo (ICAO code RKS, not an ISO 3166 state, so the certificates say XK, the user-assigned alpha-2 code)
country_pki XK EC_P256 "" ds_ext EC_P256

openssl verify -CAfile "$CSCA.cert.pem" "$DS.cert.pem" "$DS_ID.cert.pem"
openssl verify -CAfile "$UNTRUSTED_CSCA.cert.pem" "$DS_UNTRUSTED.cert.pem"
