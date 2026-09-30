#!/usr/bin/env python3
"""Summarise which keys and signature algorithms each country's document PKI uses, from ICAO PKD downloads.

SmartEmu's chip profiles mark what a document's Document Signer (the key that signs EF.SOD) uses as ASSUMED
until someone checks. The ICAO Public Key Directory publishes every participating state's Document Signer
certificates and CSCA master lists, which settles that for any state that uploads them: the key type, curve or
size, and the algorithm its CSCA signs with, by the year each certificate was issued.

Download the LDIF files from https://download.pkd.icao.int/ yourself; the ICAO PKD terms of use apply to them and
to what you publish from them. This script only reads local files and sends nothing anywhere:

    icaopkd-001-dsccrl-NNNNNN.ldif   Document Signer certificates (and CRLs, which are skipped)
    icaopkd-002-ml-NNNNNN.ldif       CSCA master lists
    *.ml                             a CSCA master list on its own, as Germany's BSI publishes one
    *.der, *.cer, *.crt, *.pem       single certificates, as some states publish them

Usage:

    tools/pkd_profile.py icaopkd-001-dsccrl-*.ldif [more files...] [--country DE] [--json out.json]

The Markdown report goes to standard output: per country and year, how many Document Signer and CSCA certificates
use each key, and the signature algorithms. Only the Python standard library is used.
"""

import argparse
import base64
import collections
import json
import re
import sys
from pathlib import Path

OIDS = {
    # Public key algorithms
    "1.2.840.113549.1.1.1": "RSA",
    "1.2.840.113549.1.1.10": "RSASSA-PSS",
    "1.2.840.10045.2.1": "EC",
    # Signature algorithms
    "1.2.840.113549.1.1.5": "sha1WithRSAEncryption",
    "1.2.840.113549.1.1.11": "sha256WithRSAEncryption",
    "1.2.840.113549.1.1.12": "sha384WithRSAEncryption",
    "1.2.840.113549.1.1.13": "sha512WithRSAEncryption",
    "1.2.840.113549.1.1.14": "sha224WithRSAEncryption",
    "1.2.840.10045.4.1": "ecdsa-with-SHA1",
    "1.2.840.10045.4.3.1": "ecdsa-with-SHA224",
    "1.2.840.10045.4.3.2": "ecdsa-with-SHA256",
    "1.2.840.10045.4.3.3": "ecdsa-with-SHA384",
    "1.2.840.10045.4.3.4": "ecdsa-with-SHA512",
    # Named curves
    "1.2.840.10045.3.1.7": "P-256",
    "1.3.132.0.33": "P-224",
    "1.3.132.0.34": "P-384",
    "1.3.132.0.35": "P-521",
    "1.3.36.3.3.2.8.1.1.1": "brainpoolP160r1",
    "1.3.36.3.3.2.8.1.1.3": "brainpoolP192r1",
    "1.3.36.3.3.2.8.1.1.5": "brainpoolP224r1",
    "1.3.36.3.3.2.8.1.1.7": "brainpoolP256r1",
    "1.3.36.3.3.2.8.1.1.9": "brainpoolP320r1",
    "1.3.36.3.3.2.8.1.1.11": "brainpoolP384r1",
    "1.3.36.3.3.2.8.1.1.13": "brainpoolP512r1",
}

OID_COUNTRY = "2.5.4.6"
OID_SIGNED_DATA = "1.2.840.113549.1.7.2"

# Many states encode curves with explicit parameters rather than a name; they are recognised by their prime
# (RFC 5639 for Brainpool, FIPS 186 for NIST)
PRIMES = {
    2**256 - 2**224 + 2**192 + 2**96 - 1: "P-256",
    2**384 - 2**128 - 2**96 + 2**32 - 1: "P-384",
    2**521 - 1: "P-521",
    int("A9FB57DBA1EEA9BC3E660A909D838D726E3BF623D52620282013481D1F6E5377", 16): "brainpoolP256r1",
    int("D35E472036BC4FB7E13C785ED201E065F98FCFA6F6F40DEF4F92B9EC7893EC28FCD412B1F1B32E27", 16): "brainpoolP320r1",
    int("8CB91E82A3386D280F5D6F7E50E641DF152F7109ED5456B412B1DA197FB71123ACD3A729901D1A71874700133107EC53", 16):
        "brainpoolP384r1",
    int("AADD9DB8DBE9C48B3FD4E6AE33C9FC07CB308DB3B3C9D20ED6639CCA703308717D4D9B009BC66842AECDA12AE6A380E62881FF2F"
        "2D82C68528AA6056583A48F3", 16): "brainpoolP512r1",
}


class DerError(ValueError):
    pass


def read_tlv(data, offset=0):
    """Returns (tag, value start, value end) of the DER element at offset."""
    if offset + 2 > len(data):
        raise DerError("truncated")
    tag = data[offset]
    offset += 1
    if tag & 0x1F == 0x1F:
        while data[offset] & 0x80:
            offset += 1
        offset += 1
    length = data[offset]
    offset += 1
    if length & 0x80:
        count = length & 0x7F
        if count == 0 or count > 4:
            raise DerError("unsupported length")
        length = int.from_bytes(data[offset:offset + count], "big")
        offset += count
    if offset + length > len(data):
        raise DerError("truncated value")
    return tag, offset, offset + length


def children(data, start, end):
    """The (tag, start, end) of each element in a constructed value."""
    result = []
    offset = start
    while offset < end:
        tag, value_start, value_end = read_tlv(data, offset)
        result.append((tag, value_start, value_end))
        offset = value_end
    return result


def decode_oid(value):
    parts = []
    number = 0
    for byte in value:
        number = (number << 7) | (byte & 0x7F)
        if not byte & 0x80:
            parts.append(number)
            number = 0
    first = min(parts[0] // 40, 2)
    return ".".join(str(p) for p in [first, parts[0] - first * 40] + parts[1:])


def name_country(data, start, end):
    """The C attribute of an X.501 Name, or None."""
    for _, set_start, set_end in children(data, start, end):
        for _, seq_start, seq_end in children(data, set_start, set_end):
            (_, oid_start, oid_end), (_, value_start, value_end) = children(data, seq_start, seq_end)[:2]
            if decode_oid(data[oid_start:oid_end]) == OID_COUNTRY:
                return data[value_start:value_end].decode("ascii", "replace").upper()
    return None


def time_year(data, tag, start, end):
    text = data[start:end].decode("ascii")
    if tag == 0x17:  # UTCTime
        year = int(text[:2])
        return 1900 + year if year >= 50 else 2000 + year
    return int(text[:4])  # GeneralizedTime


def describe_key(data, start, end):
    """A key's type and size or curve, as in "EC brainpoolP256r1" or "RSA 2048"."""
    (_, alg_start, alg_end), (_, bits_start, bits_end) = children(data, start, end)[:2]
    alg = children(data, alg_start, alg_end)
    algorithm = OIDS.get(decode_oid(data[alg[0][1]:alg[0][2]]), decode_oid(data[alg[0][1]:alg[0][2]]))
    key = data[bits_start + 1:bits_end]  # after the unused-bits byte
    if algorithm in ("RSA", "RSASSA-PSS"):
        _, seq_start, seq_end = read_tlv(key)
        _, mod_start, mod_end = children(key, seq_start, seq_end)[0]
        return f"{algorithm} {int.from_bytes(key[mod_start:mod_end], 'big').bit_length()}"
    if algorithm == "EC" and len(alg) > 1:
        tag, param_start, param_end = alg[1]
        if tag == 0x06:
            oid = decode_oid(data[param_start:param_end])
            return f"EC {OIDS.get(oid, oid)}"
        if tag == 0x30:
            # ECParameters: version, fieldID { prime-field, p }, curve, base, order, cofactor
            field = children(data, param_start, param_end)[1]
            _, prime_start, prime_end = children(data, field[1], field[2])[1]
            prime = int.from_bytes(data[prime_start:prime_end], "big")
            return f"EC {PRIMES.get(prime, f'unrecognised {prime.bit_length()}-bit curve')} (explicit)"
    return algorithm


def parse_certificate(der):
    """What the report needs of an X.509 certificate."""
    _, cert_start, cert_end = read_tlv(der)
    tbs, signature_algorithm = children(der, cert_start, cert_end)[:2]
    fields = children(der, tbs[1], tbs[2])
    if fields[0][0] == 0xA0:  # explicit version
        fields = fields[1:]
    _, issuer, validity, subject, public_key = fields[1:6]
    not_before = children(der, validity[1], validity[2])[0]
    sig_oid_element = children(der, signature_algorithm[1], signature_algorithm[2])[0]
    sig_oid = decode_oid(der[sig_oid_element[1]:sig_oid_element[2]])
    return {
        "country": name_country(der, subject[1], subject[2]) or name_country(der, issuer[1], issuer[2]) or "??",
        "year": time_year(der, *not_before),
        "key": describe_key(der, public_key[1], public_key[2]),
        "signature": OIDS.get(sig_oid, sig_oid),
    }


def master_list_certificates(der):
    """The CSCA certificates in a CMS-signed CSCA master list (ICAO 9303 part 12)."""
    _, info_start, info_end = read_tlv(der)
    content_type, content = children(der, info_start, info_end)[:2]
    if decode_oid(der[content_type[1]:content_type[2]]) != OID_SIGNED_DATA:
        raise DerError("not CMS signed data")
    _, signed_start, signed_end = read_tlv(der, content[1])
    encapsulated = children(der, signed_start, signed_end)[2]
    explicit = children(der, encapsulated[1], encapsulated[2])[1]
    _, octets_start, octets_end = read_tlv(der, explicit[1])
    master_list = der[octets_start:octets_end]
    _, list_start, list_end = read_tlv(master_list)
    _, certs_start, certs_end = children(master_list, list_start, list_end)[1]
    offset = certs_start
    while offset < certs_end:
        _, _, end = read_tlv(master_list, offset)
        yield master_list[offset:end]
        offset = end


def ldif_entries(text):
    """Each LDIF entry's attributes, with folded lines joined, as (name, value, is_base64) tuples."""
    entry = []
    for line in re.sub(r"\r?\n ", "", text).splitlines():
        if not line.strip():
            if entry:
                yield entry
            entry = []
            continue
        if line.startswith("#") or ":" not in line:
            continue
        name, _, value = line.partition(":")
        is_base64 = value.startswith(":")
        entry.append((name.strip(), value[1:].strip() if is_base64 else value.strip(), is_base64))
    if entry:
        yield entry


def read_certificates(path):
    """(kind, DER) of every certificate in a file: DS for Document Signers, CSCA for master list entries."""
    data = path.read_bytes()
    if path.suffix.lower() == ".ldif":
        for entry in ldif_entries(data.decode("utf-8", "replace")):
            for name, value, is_base64 in entry:
                if not is_base64:
                    continue
                if name.lower().startswith("usercertificate"):
                    yield "DS", base64.b64decode(value)
                elif name.lower().startswith("pkdmasterlistcontent"):
                    try:
                        for der in master_list_certificates(base64.b64decode(value)):
                            yield "CSCA", der
                    except (DerError, IndexError) as error:
                        print(f"{path}: skipped a master list: {error}", file=sys.stderr)
        return
    if path.suffix.lower() == ".ml":
        for der in master_list_certificates(data):
            yield "CSCA", der
        return
    if b"-----BEGIN CERTIFICATE-----" in data:
        for block in re.findall(rb"-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", data, re.S):
            yield "DS", base64.b64decode(b"".join(block.split()))
    else:
        yield "DS", data


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("files", nargs="+", type=Path, help="ICAO PKD LDIF files or certificate files")
    parser.add_argument("--country", action="append", help="only this ISO 3166 alpha-2 country, as in DE; repeatable")
    parser.add_argument("--json", type=Path, help="also write the counts as JSON to this file")
    args = parser.parse_args()
    countries = {c.upper() for c in args.country} if args.country else None

    # country -> kind -> year -> Counter of (key, signature)
    counts = collections.defaultdict(lambda: collections.defaultdict(lambda: collections.defaultdict(collections.Counter)))
    skipped = 0
    for path in args.files:
        for kind, der in read_certificates(path):
            try:
                cert = parse_certificate(der)
            except (DerError, IndexError, ValueError, UnicodeDecodeError):
                skipped += 1
                continue
            if countries and cert["country"] not in countries:
                continue
            counts[cert["country"]][kind][cert["year"]][(cert["key"], cert["signature"])] += 1

    print("# Document PKI by country\n")
    print("From ICAO PKD data; subject to the ICAO PKD terms of use. `DS` is a Document Signer, the key that signs "
          "EF.SOD; `CSCA` the country signing CA. The signature algorithm is the one the certificate is signed with, "
          "by the CSCA.\n")
    for country in sorted(counts):
        print(f"## {country}\n")
        print("| Kind | Year | Key | Signed with | Certificates |")
        print("|---|---|---|---|---|")
        for kind in ("CSCA", "DS"):
            for year in sorted(counts[country][kind]):
                for (key, signature), count in counts[country][kind][year].most_common():
                    print(f"| {kind} | {year} | {key} | {signature} | {count} |")
        print()
    if skipped:
        print(f"{skipped} certificates couldn't be parsed and were skipped.", file=sys.stderr)

    if args.json:
        report = {
            country: {
                kind: {
                    str(year): [{"key": key, "signature": signature, "count": count}
                                for (key, signature), count in by_key.most_common()]
                    for year, by_key in sorted(by_year.items())
                }
                for kind, by_year in by_kind.items()
            }
            for country, by_kind in sorted(counts.items())
        }
        args.json.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
