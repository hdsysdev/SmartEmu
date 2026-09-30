# PassportEmu test PKI

A test ICAO 9303 part 12 PKI for passport passive authentication. PassportEmu signs EF.SOD with the
chosen profile's Document Signer, and with PACE-CAM also EF.CardSecurity. A verifier trusting the
matching test CSCA can verify these signatures. The generic anchor retains its existing subject,
**SmartEmu Test CSCA**. ID cards and residence
permits (TD1) are signed by a second Document Signer whose document type list allows them.

The chip faults the app can inject use three more certificates on purpose: a Document Signer that has
expired, and one issued by an **Unknown CSCA** that nothing should trust. A verifier set up as below
should reject documents signed by either.

**These keys are public and for testing only.** Never trust this CSCA in production.

## Contents

| File | What it is |
|---|---|
| `csca/smartemu-test-csca.cert.pem` / `.der` | Country Signing CA certificate, self-signed. The trust anchor to install in test environments |
| `csca/smartemu-test-csca.key.pem` | CSCA private key (PKCS#8, unencrypted), for issuing more test Document Signers |
| `document-signer/smartemu-test-ds.cert.pem` / `.der` | Generic profile's Document Signer certificate issued by the CSCA; embedded in its EF.SOD and EF.CardSecurity |
| `document-signer/smartemu-test-ds.key.pem` | Document Signer private key (PKCS#8, unencrypted); the app signs EF.SOD and EF.CardSecurity with it |
| `document-signer/smartemu-test-ds.p12` | Document Signer key, certificate and CSCA chain as PKCS#12, password `smartemu` |
| `document-signer/smartemu-test-ds-id.*` | Document Signer for ID cards and residence permits (document types `ID` and `IR`), issued by the CSCA |
| `document-signer/smartemu-test-ds-expired.*` | Issued by the CSCA, but valid for one day only; the "Expired Document Signer" fault signs with it |
| `document-signer/smartemu-test-ds-untrusted.*` | Issued by the Unknown CSCA; the "Untrusted CSCA" fault signs with it |
| `csca/smartemu-test-csca-<cc>.*`, `document-signer/smartemu-test-ds-<cc>*.*` | Test CSCAs and Document Signers for country chip profiles (DE, NL, GB, US, FR, BE, ES, IT, CH, CA, AU); see [Country test PKIs](#country-test-pkis) |
| `untrusted-csca/smartemu-unknown-csca.*` | A second self-signed CSCA. Never install it: it's there to be untrusted |
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

The other certificates share those profiles, keys (EC P-256) and signatures:

| Certificate | Issuer | Valid | Document types | SHA-256 fingerprint |
|---|---|---|---|---|
| Document Signer for ID documents | SmartEmu Test CSCA | 2026-09-30 to 2036-09-29 | `ID`, `IR` | `80:0F:B6:C3:0D:B9:58:9A:36:70:F5:42:D7:73:21:63:53:C2:EE:11:0D:AC:5E:05:BD:B7:A8:B7:AE:42:BD:4A` |
| Document Signer (expired) | SmartEmu Test CSCA | 2026-09-23 to 2026-09-24 | `P`, `ID`, `IR` | `21:A5:67:42:0B:43:ED:7D:0C:DE:E6:D0:C6:94:A3:73:47:16:9B:C5:93:6F:F6:DF:0F:EA:72:15:3C:4B:05:61` |
| Document Signer (untrusted) | SmartEmu Unknown CSCA | 2026-09-30 to 2036-09-29 | `P`, `ID`, `IR` | `8D:7F:30:0A:1B:2C:5F:51:B6:C9:83:C6:95:D1:E7:CF:93:38:9C:74:14:DD:2A:E6:B6:1F:22:52:81:20:A9:F1` |
| Unknown CSCA | self-signed | 2026-09-30 to 2046-09-30 | | `24:06:1E:AD:77:A3:89:E7:5B:8C:FD:AA:4A:64:87:42:47:F4:B9:E9:F3:2E:F0:FD:CB:B4:E0:2D:57:A5:8A:F1` |

### Country test PKIs

Country profiles use test certificates whose country code matches the configured issuing state.
Every certificate has `TEST` or `Test` in its CN and `OU=Test PKI`. Existing generic and DE/NL/GB/US subjects
retain `O=SmartEmu`; new FR/BE/ES/IT/CH/CA/AU subjects use `O=PassportEmu`. Legacy resource basenames
remain `smartemu-test-*`. These are emulator trust anchors and signers, not real issuer certificates.

CSCA keys do not establish document signer, SOD, AA, CA or PACE algorithms. Most configured suites are
ASSUMED; [profile evidence and generation boundaries](../docs/chip-profile-evidence.md) explain the
specific supported claims. The legacy certificate details below are preserved.

| Certificate | Subject CN (C) | Key | Valid | Document types | SHA-256 fingerprint |
|---|---|---|---|---|---|
| `csca/smartemu-test-csca-de` | SmartEmu Test CSCA DE (DE) | EC brainpoolP256r1 | 2026-09-30 to 2046-09-30 | | `D7:86:C0:5A:0D:93:51:AB:9C:8C:69:B5:5A:C4:77:C4:62:6F:01:AB:09:50:AE:F2:4B:C2:3B:2C:A7:1F:26:EA` |
| `document-signer/smartemu-test-ds-de` | SmartEmu TEST Document Signer DE (DE) | EC brainpoolP256r1 | 2026-09-30 to 2036-09-29 | `P` | `F0:84:85:BA:AA:EF:F0:7C:E2:08:D0:63:8E:1C:00:4E:EB:0E:79:75:38:95:7E:F5:D7:89:96:AE:64:2E:EB:4C` |
| `document-signer/smartemu-test-ds-de-id` | SmartEmu TEST Document Signer DE ID documents (DE) | EC brainpoolP256r1 | 2026-09-30 to 2036-09-29 | `ID`, `IR` | `92:62:E8:46:40:F4:DE:9E:B4:27:0E:82:29:8B:09:F4:16:E7:C3:EC:35:01:BB:40:F1:04:F1:FE:F9:6E:50:35` |
| `csca/smartemu-test-csca-nl` | SmartEmu Test CSCA NL (NL) | EC P-256 | 2026-09-30 to 2046-09-30 | | `C3:3D:69:A9:E1:DC:41:DD:E1:BC:39:C6:45:9B:A9:A4:E5:0F:13:92:12:95:82:95:C7:E8:EA:0D:C6:EB:37:CB` |
| `document-signer/smartemu-test-ds-nl` | SmartEmu TEST Document Signer NL (NL) | EC P-256 | 2026-09-30 to 2036-09-29 | `P` | `B9:40:59:2D:8E:9E:45:FC:AF:7E:D3:68:8D:0C:57:11:0F:34:D8:CE:A4:71:5D:81:9C:AD:56:85:1E:AB:7C:BD` |
| `document-signer/smartemu-test-ds-nl-id` | SmartEmu TEST Document Signer NL ID documents (NL) | EC P-256 | 2026-09-30 to 2036-09-29 | `I`, `ID` | `91:0F:33:6D:E5:AA:4F:C9:42:0F:78:8B:5A:08:E6:C3:26:A1:51:CE:43:0D:69:04:73:D6:0D:8D:A1:43:71:09` |
| `document-signer/smartemu-test-ds-nl-rsa` | SmartEmu TEST Document Signer NL RSA (NL) | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `2E:D0:9C:36:04:75:0B:C8:EF:9C:F9:BB:C7:CB:E2:EC:D7:34:16:F9:13:B0:47:87:86:E0:51:F6:CC:97:3E:CF` |
| `csca/smartemu-test-csca-gb` | SmartEmu Test CSCA GB (GB) | EC P-256 | 2026-09-30 to 2046-09-30 | | `80:1B:4A:3A:E8:B6:10:70:CD:3A:5D:1D:1D:24:5E:BA:19:B2:46:80:8B:FC:C7:93:AA:63:09:E7:A5:20:DD:A3` |
| `document-signer/smartemu-test-ds-gb` | SmartEmu TEST Document Signer GB (GB) | EC P-256 | 2026-09-30 to 2036-09-29 | `P` | `B0:B5:34:8D:9E:AE:8D:97:15:E8:82:72:96:8F:F3:E5:B0:E3:26:C5:31:B9:3B:8A:0C:FE:0D:5D:F6:97:46:48` |
| `csca/smartemu-test-csca-us` | SmartEmu Test CSCA US (US) | EC P-256 | 2026-09-30 to 2046-09-30 | | `C6:51:4A:FE:49:B2:59:83:9A:B9:F6:C8:45:ED:9E:1F:7C:39:43:52:1D:9E:E4:10:81:6C:0F:F3:FA:8E:34:ED` |
| `document-signer/smartemu-test-ds-us` | SmartEmu TEST Document Signer US (US) | EC P-256 | 2026-09-30 to 2036-09-29 | `P` | `69:C4:9D:2B:4C:8C:48:A9:09:C7:63:A4:76:D2:71:1C:67:86:4F:98:02:94:CA:3A:CE:70:1C:3B:99:03:1E:BA` |

All are signed with ecdsa-with-SHA256 by their matching test CSCA. The Dutch RSA signer is for the 2006 passport
profile, whose EF.SOD is signed with SHA256withRSA (PKCS#1 v1.5). To check a chain:
`openssl verify -CAfile csca/smartemu-test-csca-de.cert.pem document-signer/smartemu-test-ds-de.cert.pem`.

The nine added passport profiles share seven new country PKIs. Each includes `.key.pem`, `.cert.pem`
and `.cert.der` for both CSCA and Document Signer. Their CNs are `PassportEmu TEST CSCA XX` and
`PassportEmu TEST Document Signer XX`, with the uppercase country code in place of `XX`.

| Country | Resource suffix | CSCA key | Document Signer key | Scope |
|---|---|---|---|---|
| France | `fr` | EC P-256 | EC P-256 | ASSUMED signer for the historical 2008 study preset |
| Belgium | `be` | EC P-256 | RSA 2048 | DS key size from the 2008 authors' Table 2; SOD padding and separate LDS digest remain ASSUMED |
| Spain | `es` | EC P-256 | EC P-256 | ASSUMED signer shared by the historical study and third-generation presets |
| Italy | `it` | EC P-256 | EC P-256 | ASSUMED signer for the historical 2008 study preset |
| Switzerland | `ch` | EC P-256 | EC P-256 | ASSUMED signer for the 2007 study sample |
| Canada | `ca` | EC P-256 | RSA 2048 | ASSUMED PKCS#1 v1.5 stand-in; issuer gives RSA-PSS/SHA-256 examples |
| Australia | `au` | EC P-256 | EC P-256 | ASSUMED signer shared by M and N Series presets |

All seven new CSCA keys are emulator choices. Certificate issuer signatures use ECDSA/SHA-256,
including certificates carrying an RSA DS public key; this certificate signature does not establish
the SOD signature. For Belgium the test SOD uses SHA-1 with RSA, and for Canada SHA-256 with RSA,
both with PKCS#1 v1.5 padding. No algorithm is inferred from a master-list CSCA.

`UT` ("Utopia") is the country ICAO uses for specimen documents. The issuing state of an emulated
passport is whatever the PassportEmu form says, so verifiers that match the CSCA country to the document's
issuing state will flag a mismatch; configure them to accept this CSCA for any state in test environments.

## Using it elsewhere

- **Passive authentication**: add `csca/smartemu-test-csca.cert.pem` to the verifier's CSCA trust store
  (or test master list). The Document Signer certificate is inside each EF.SOD, so it needs no installation.
- **Check the chain**: `openssl verify -CAfile csca/smartemu-test-csca.cert.pem document-signer/smartemu-test-ds.cert.pem`
- **Sign test SODs in other tools**: use `document-signer/smartemu-test-ds.p12` (password `smartemu`), or the PEM key and certificate.

## Regenerating

`./generate.sh` only creates what's missing, so it's safe to run to add a certificate. `FORCE=1 ./generate.sh`
replaces every key and certificate. That breaks every environment that trusts
the current CSCA, so only do it deliberately (for example before the Document Signer expires in 2036),
then update the table above.
