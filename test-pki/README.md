# SmartEmu test PKI

A test ICAO 9303 part 12 PKI for passport passive authentication. SmartEmu signs the EF.SOD of every
emulated passport with the Document Signer below, and with PACE-CAM also EF.CardSecurity, so any system
that trusts the **SmartEmu Test CSCA** can verify SmartEmu passports end to end.

**These keys are public and for testing only.** Never trust this CSCA in production.

## Contents

| File | What it is |
|---|---|
| `csca/smartemu-test-csca.cert.pem` / `.der` | Country Signing CA certificate, self-signed. The trust anchor to install in test environments |
| `csca/smartemu-test-csca.key.pem` | CSCA private key (PKCS#8, unencrypted), for issuing more test Document Signers |
| `document-signer/smartemu-test-ds.cert.pem` / `.der` | Document Signer certificate issued by the CSCA; embedded in every EF.SOD and EF.CardSecurity |
| `document-signer/smartemu-test-ds.key.pem` | Document Signer private key (PKCS#8, unencrypted); the app signs EF.SOD and EF.CardSecurity with it |
| `document-signer/smartemu-test-ds.p12` | Document Signer key, certificate and CSCA chain as PKCS#12, password `smartemu` |
| `openssl.cnf`, `generate.sh` | Certificate profiles and the script that generated everything |

The app bundles the `document-signer` directory as Java resources (see `composeApp/build.gradle.kts`).

## Certificates

| | CSCA | Document Signer |
|---|---|---|
| Subject | `C=UT, O=SmartEmu, OU=Test PKI, CN=SmartEmu Test CSCA` | `C=UT, O=SmartEmu, OU=Test PKI, CN=SmartEmu Test Document Signer` |
| Key | EC P-256 | EC P-256 |
| Signature | ecdsa-with-SHA256 | ecdsa-with-SHA256 (issued by the CSCA); SOD signed with SHA256withECDSA |
| Valid | 2026-09-23 to 2046-09-23 | 2026-09-23 to 2036-09-22 |
| Extensions | basicConstraints CA, pathlen 0; keyUsage keyCertSign, cRLSign | keyUsage digitalSignature; ICAO document type list `P` |
| SHA-256 fingerprint | `9A:BF:AA:1E:25:65:08:08:5E:65:E3:7B:55:1D:A9:28:5F:00:E3:F7:11:61:BF:F4:CD:D6:82:4C:FD:69:15:48` | `06:2B:2C:F6:A9:98:F4:EE:95:4A:D9:E1:71:25:5A:B7:C4:79:7D:38:61:E6:C6:DE:18:D5:B3:1D:F0:53:1B:5F` |

`UT` ("Utopia") is the country ICAO uses for specimen documents. The issuing state of an emulated
passport is whatever the SmartEmu form says, so verifiers that match the CSCA country to the document's
issuing state will flag a mismatch; configure them to accept this CSCA for any state in test environments.

## Using it elsewhere

- **Passive authentication**: add `csca/smartemu-test-csca.cert.pem` to the verifier's CSCA trust store
  (or test master list). The Document Signer certificate is inside each EF.SOD, so it needs no installation.
- **Check the chain**: `openssl verify -CAfile csca/smartemu-test-csca.cert.pem document-signer/smartemu-test-ds.cert.pem`
- **Sign test SODs in other tools**: use `document-signer/smartemu-test-ds.p12` (password `smartemu`), or the PEM key and certificate.

## Regenerating

`FORCE=1 ./generate.sh` replaces every key and certificate. That breaks every environment that trusts
the current CSCA, so only do it deliberately (for example before the Document Signer expires in 2036),
then update the table above.
