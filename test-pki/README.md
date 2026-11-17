# PassportEmu test PKI

A test ICAO 9303 part 12 PKI for passport passive authentication. PassportEmu signs EF.SOD with the
chosen profile's Document Signer, and with PACE-CAM also EF.CardSecurity. A verifier that trusts the
matching test CSCA can verify these signatures. The generic profiles chain to **PassportEmu Test CSCA**;
each country profile chains to a test CSCA of its own country. ID cards and residence permits (TD1) are
signed by Document Signers whose document type list allows them.

The chip faults the app can inject use three more certificates on purpose: a Document Signer that has
expired, and one issued by an **Unknown CSCA** that nothing should trust. A verifier set up as below
should reject documents signed by either.

**These keys are public and for testing only.** Their private keys are in this repository, so anyone can sign
with them. Never trust any of these CSCAs in production, and never add them to a real master list.

## Contents

| File | What it is |
|---|---|
| `csca/passportemu-test-csca.cert.pem` / `.der` | Generic Country Signing CA certificate, self-signed. The trust anchor to install in test environments |
| `csca/passportemu-test-csca.key.pem` | Its private key (PKCS#8, unencrypted), for issuing more test Document Signers |
| `document-signer/passportemu-test-ds.*` | Generic passport Document Signer: certificate (embedded in EF.SOD and EF.CardSecurity) and private key |
| `document-signer/passportemu-test-ds-id.*` | Document Signer for ID cards and residence permits (document types `ID` and `IR`) |
| `document-signer/passportemu-test-ds-expired.*` | Valid for one day only, long past; the "Expired Document Signer" fault signs with it |
| `document-signer/passportemu-test-ds-untrusted.*` | Issued by the Unknown CSCA; the "Untrusted CSCA" fault signs with it |
| `csca/passportemu-test-csca-<cc>.*`, `document-signer/passportemu-test-ds-<cc>*.*` | Test CSCAs and Document Signers for the country chip profiles; see [Country test PKIs](#country-test-pkis) |
| `untrusted-csca/passportemu-unknown-csca.*` | A second self-signed CSCA. Never install it: it's there to be untrusted |
| `openssl.cnf`, `generate.sh` | Certificate profiles and the script that generated everything |

The app bundles the `document-signer` directory as Java resources and reads `<name>.cert.pem` and `<name>.key.pem`;
the DER copies are kept out of the APK (see `composeApp/build.gradle.kts`).

## Certificates

Every certificate has `O=PassportEmu, OU=Test PKI` and a `PassportEmu Test` CN, and starts on 2026-11-16, not on
the day it was generated, so that a reader whose clock is a little behind still accepts it. CSCAs run 20 years and
Document Signers 10 years (the expired one, a day). CSCAs have basicConstraints CA with pathlen 0 and keyUsage
keyCertSign, cRLSign; Document Signers have keyUsage digitalSignature and the ICAO document type list extension.
Every certificate is signed with ecdsa-with-SHA256 by its CSCA, whatever the Document Signer's own key type; that
signature does not establish the SOD signature algorithm, which the profile sets.

`UT` ("Utopia") is the country ICAO uses for specimen documents. The issuing state of an emulated document with a
generic profile is whatever the PassportEmu form says, so a verifier that matches the CSCA country to the document's
issuing state will flag a mismatch; configure it to accept the generic CSCA for any state in test environments, or
use a country profile.

### Generic and fault certificates

| Certificate | Subject CN (C) | Issuer | Key | Valid | Document types | SHA-256 fingerprint |
|---|---|---|---|---|---|---|
| `csca/passportemu-test-csca` | PassportEmu Test CSCA (UT) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `F5:70:4C:89:48:4F:CB:92:61:72:EF:CC:29:3D:AD:7C:6C:95:06:98:1E:65:92:3C:7A:6D:A1:41:51:34:31:D2` |
| `untrusted-csca/passportemu-unknown-csca` | PassportEmu Unknown CSCA (UT) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `1F:8B:F0:22:B7:12:DA:E3:83:D7:55:8B:ED:5F:35:60:D0:EE:19:B8:E8:23:EB:D5:D4:DD:C5:9C:0E:BB:05:95` |
| `document-signer/passportemu-test-ds` | PassportEmu Test Document Signer (UT) | PassportEmu Test CSCA | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `1C:83:00:9C:EF:A3:8E:72:03:ED:D2:2C:EC:9A:5C:F8:DD:1B:D6:E9:45:52:EC:27:20:40:69:E4:08:23:26:2B` |
| `document-signer/passportemu-test-ds-id` | PassportEmu Test Document Signer for ID documents (UT) | PassportEmu Test CSCA | EC P-256 | 2026-11-16 to 2036-11-15 | `ID`, `IR` | `F2:A0:33:DE:C5:76:EE:22:74:37:84:0E:26:A5:D7:A1:13:4B:42:00:82:1D:6B:C1:3B:AC:52:DE:3E:6A:30:AA` |
| `document-signer/passportemu-test-ds-expired` | PassportEmu Test Document Signer (expired) (UT) | PassportEmu Test CSCA | EC P-256 | 2026-11-16 to 2026-11-17 | `P`, `ID`, `IR` | `7D:2C:6C:E9:C6:2A:41:FD:4C:F1:73:06:5D:80:25:F0:94:2F:4B:77:92:7E:F3:88:4C:FD:8D:73:4F:B8:90:D9` |
| `document-signer/passportemu-test-ds-untrusted` | PassportEmu Test Document Signer (untrusted) (UT) | PassportEmu Unknown CSCA | EC P-256 | 2026-11-16 to 2036-11-15 | `P`, `ID`, `IR` | `7F:E9:2E:33:55:80:1C:70:94:D9:DD:73:92:77:E2:83:1C:6B:18:53:51:22:63:74:E9:C1:84:5F:99:94:6D:11` |

### Country test PKIs

| Certificate | Subject CN (C) | Issuer | Key | Valid | Document types | SHA-256 fingerprint |
|---|---|---|---|---|---|---|
| `csca/passportemu-test-csca-at` | PassportEmu Test CSCA AT (AT) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `D7:C4:0B:D6:19:5E:8F:56:2D:1D:86:50:78:96:3F:62:40:3D:16:18:32:BB:F4:3E:0A:46:B2:69:63:88:56:D6` |
| `document-signer/passportemu-test-ds-at` | PassportEmu Test Document Signer AT (AT) | PassportEmu Test CSCA AT | EC brainpoolP256r1 | 2026-11-16 to 2036-11-15 | `P` | `CC:15:94:8C:A1:9D:20:62:30:B8:A3:04:BD:A9:B2:5E:63:F6:CE:DF:2B:34:C0:76:38:3F:6A:40:83:B5:7D:FD` |
| `csca/passportemu-test-csca-au` | PassportEmu Test CSCA AU (AU) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `09:A6:AA:71:BF:F6:9E:65:FD:16:4F:CF:1F:9D:95:1A:08:C8:E0:F9:F9:88:A0:74:27:A8:1F:B4:B0:D2:95:B5` |
| `document-signer/passportemu-test-ds-au-rsa` | PassportEmu Test Document Signer AU RSA (AU) | PassportEmu Test CSCA AU | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `5B:EF:7D:CC:35:65:C9:DE:D3:4C:E9:61:96:E3:FD:65:43:6A:94:91:5D:2B:56:10:D0:E2:E6:02:48:67:8D:9D` |
| `document-signer/passportemu-test-ds-au` | PassportEmu Test Document Signer AU (AU) | PassportEmu Test CSCA AU | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `A5:C5:47:E5:54:F7:DA:1D:4B:61:F2:D1:5B:92:90:4C:53:49:B0:D3:93:5D:05:D9:C9:CD:32:FE:0F:D4:31:27` |
| `csca/passportemu-test-csca-be` | PassportEmu Test CSCA BE (BE) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `B0:B4:EF:03:77:1A:CB:22:26:45:62:B3:56:1D:64:87:97:67:BD:EC:CC:21:84:1D:23:67:B1:E4:F2:87:61:DB` |
| `document-signer/passportemu-test-ds-be` | PassportEmu Test Document Signer BE (BE) | PassportEmu Test CSCA BE | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `B4:9C:64:BD:8E:44:AE:0F:A2:2C:28:47:56:1B:05:04:08:68:73:F1:DD:6D:E0:9F:51:01:73:6C:0E:68:1B:46` |
| `csca/passportemu-test-csca-ca` | PassportEmu Test CSCA CA (CA) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `10:AD:54:94:0E:5C:86:53:1D:BE:41:9C:E0:B2:6D:F4:BE:57:90:AF:DF:52:55:FC:57:E0:2D:C8:C5:C1:BC:4C` |
| `document-signer/passportemu-test-ds-ca` | PassportEmu Test Document Signer CA (CA) | PassportEmu Test CSCA CA | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `4C:12:9A:3F:C8:2E:81:42:D4:BC:07:18:57:CF:01:11:13:88:F9:F9:1E:93:B9:6C:72:B5:30:BD:24:48:04:E9` |
| `csca/passportemu-test-csca-ch` | PassportEmu Test CSCA CH (CH) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `05:F5:D6:66:2F:DF:79:E2:16:EF:B3:FD:3A:E2:02:36:45:6A:FD:19:8A:5F:3D:C8:BE:37:E8:82:A7:3A:18:3A` |
| `document-signer/passportemu-test-ds-ch` | PassportEmu Test Document Signer CH (CH) | PassportEmu Test CSCA CH | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `60:C6:68:30:27:99:D5:70:10:E1:DB:1E:97:C9:D5:47:AD:BD:1D:FE:EB:D7:EE:F7:54:5B:18:1B:F3:48:3C:C1` |
| `csca/passportemu-test-csca-cn` | PassportEmu Test CSCA CN (CN) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `4C:98:38:60:0C:3F:01:EF:DC:F1:8C:46:3E:EF:4B:DD:6C:CF:B0:0D:C2:B0:F8:3F:6E:F4:56:FB:18:83:CE:B1` |
| `document-signer/passportemu-test-ds-cn` | PassportEmu Test Document Signer CN (CN) | PassportEmu Test CSCA CN | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `1C:4E:46:86:AE:32:C8:95:66:92:6F:47:79:20:00:11:F4:D1:5C:C8:1A:E1:AD:E4:DF:8C:D4:4E:DC:47:B9:61` |
| `csca/passportemu-test-csca-de` | PassportEmu Test CSCA DE (DE) | self-signed | EC brainpoolP256r1 | 2026-11-16 to 2046-11-16 |  | `A6:0B:95:B9:74:A1:8E:B1:B7:9C:52:82:E7:85:D2:42:67:CC:97:FF:D5:83:36:22:80:35:FA:06:59:84:7E:0D` |
| `document-signer/passportemu-test-ds-de-id` | PassportEmu Test Document Signer DE ID documents (DE) | PassportEmu Test CSCA DE | EC brainpoolP256r1 | 2026-11-16 to 2036-11-15 | `ID`, `IR` | `94:D3:43:7D:B2:1F:EE:D2:C3:E2:4C:CD:44:03:5D:E6:67:08:EC:95:90:00:A7:5D:F0:BB:C9:9F:4B:9C:5F:60` |
| `document-signer/passportemu-test-ds-de-p384` | PassportEmu Test Document Signer DE P-384 (DE) | PassportEmu Test CSCA DE | EC brainpoolP384r1 | 2026-11-16 to 2036-11-15 | `P` | `B5:3E:95:0A:42:2C:5B:F3:23:B5:4F:E8:29:B5:FF:61:BF:A3:EE:AF:93:4C:4B:01:F4:B6:40:DF:B3:9B:A0:E3` |
| `document-signer/passportemu-test-ds-de` | PassportEmu Test Document Signer DE (DE) | PassportEmu Test CSCA DE | EC brainpoolP256r1 | 2026-11-16 to 2036-11-15 | `P` | `A4:61:E3:37:81:36:D4:72:E2:6B:3E:00:9E:D8:7D:FF:51:E7:C9:14:82:8E:6A:15:5A:DB:E0:A8:4E:91:14:A1` |
| `csca/passportemu-test-csca-es` | PassportEmu Test CSCA ES (ES) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `21:46:B8:C9:BB:79:42:3D:CF:24:CB:0C:B8:02:2D:8B:E8:F8:A3:A9:9F:22:B7:81:79:C6:81:D3:45:AA:16:CF` |
| `document-signer/passportemu-test-ds-es` | PassportEmu Test Document Signer ES (ES) | PassportEmu Test CSCA ES | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `32:53:14:03:2D:DD:81:23:C8:78:07:86:D7:85:9B:12:16:D9:96:2E:94:F3:EB:AA:4A:D3:0A:46:71:34:27:05` |
| `csca/passportemu-test-csca-fi` | PassportEmu Test CSCA FI (FI) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `FD:5B:78:EC:2A:03:B4:27:B3:E0:44:C7:83:40:5D:60:4C:44:96:47:DB:6D:80:80:84:D5:9F:98:6D:22:F7:21` |
| `document-signer/passportemu-test-ds-fi-id` | PassportEmu Test Document Signer FI ID documents (FI) | PassportEmu Test CSCA FI | EC brainpoolP512r1 | 2026-11-16 to 2036-11-15 | `ID`, `IR` | `01:5B:2F:92:47:67:32:49:8E:68:82:6A:62:90:E5:6F:20:50:47:53:8F:A5:BB:A4:01:24:62:8D:8B:1E:56:63` |
| `document-signer/passportemu-test-ds-fi` | PassportEmu Test Document Signer FI (FI) | PassportEmu Test CSCA FI | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `0C:3A:42:8E:65:70:E9:A6:8C:45:5C:A8:23:BA:D3:53:46:2F:03:CD:77:CD:16:96:1D:F2:BB:4C:9D:17:5A:7A` |
| `csca/passportemu-test-csca-fr` | PassportEmu Test CSCA FR (FR) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `EA:6F:78:47:AF:66:AF:4D:5C:39:01:ED:A7:98:B7:FC:1D:E8:0E:4F:3F:60:D0:8F:B7:65:EC:2B:8F:C8:37:0E` |
| `document-signer/passportemu-test-ds-fr-rsa` | PassportEmu Test Document Signer FR RSA (FR) | PassportEmu Test CSCA FR | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `C8:03:58:CF:E9:01:B2:0D:7D:50:FA:68:D4:77:33:29:26:D0:90:D9:B0:05:86:8F:F1:CD:3A:DD:B2:5B:D5:FC` |
| `document-signer/passportemu-test-ds-fr` | PassportEmu Test Document Signer FR (FR) | PassportEmu Test CSCA FR | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `C8:3E:B0:ED:E6:F6:36:DC:E9:9E:42:2B:58:DD:6E:C8:7C:D6:12:08:8F:2C:8D:A0:54:32:D3:62:B1:23:20:B9` |
| `csca/passportemu-test-csca-gb` | PassportEmu Test CSCA GB (GB) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `21:EE:B0:20:5B:53:E0:4D:A1:5D:D5:64:BD:FA:06:29:EB:F2:23:48:CA:D9:EE:E0:44:54:8C:51:71:3A:E1:7E` |
| `document-signer/passportemu-test-ds-gb` | PassportEmu Test Document Signer GB (GB) | PassportEmu Test CSCA GB | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `26:7E:C7:B2:8C:EC:F1:9D:BB:90:B4:5E:F6:4D:50:8E:E5:B9:8A:15:7A:22:7F:08:FA:09:7B:BB:3F:10:A4:75` |
| `csca/passportemu-test-csca-hk` | PassportEmu Test CSCA HK (HK) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `1D:EE:A0:64:7D:E0:39:9F:25:3A:39:B1:DF:80:FD:BE:DE:6D:F0:A5:3A:CF:03:76:92:4F:98:5B:BF:75:8F:54` |
| `document-signer/passportemu-test-ds-hk` | PassportEmu Test Document Signer HK (HK) | PassportEmu Test CSCA HK | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `86:58:AF:BD:64:0A:29:FA:C4:E2:53:5E:5A:EF:5B:2A:A8:97:C4:DC:43:D0:A8:19:FC:DE:9A:AC:CC:AC:50:59` |
| `csca/passportemu-test-csca-idn` | PassportEmu Test CSCA IDN (ID) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `22:BD:9E:24:06:4D:F3:B4:11:CD:27:D1:5B:43:97:3B:F1:A9:49:5D:38:44:27:E2:74:D0:DD:38:6F:E7:D1:F4` |
| `document-signer/passportemu-test-ds-idn` | PassportEmu Test Document Signer IDN (ID) | PassportEmu Test CSCA IDN | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `D9:B1:60:3E:86:3D:AD:48:4E:C7:C0:4C:A8:DD:51:30:AD:8B:B9:78:39:48:BC:1F:1E:63:D3:BE:71:52:C4:4E` |
| `csca/passportemu-test-csca-it` | PassportEmu Test CSCA IT (IT) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `C6:E2:08:AA:88:75:41:B3:0D:2B:7B:B9:FF:18:CD:7F:65:D9:D8:74:34:D9:37:2C:B3:6D:77:F3:BC:E2:43:32` |
| `document-signer/passportemu-test-ds-it` | PassportEmu Test Document Signer IT (IT) | PassportEmu Test CSCA IT | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `22:B1:1F:B3:D7:DF:47:9E:DA:B8:1A:0E:1B:54:30:73:63:EE:23:3E:D9:C4:B4:9D:74:AE:E4:B4:F7:2A:E1:56` |
| `csca/passportemu-test-csca-my` | PassportEmu Test CSCA MY (MY) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `A4:BF:8A:8C:42:F6:32:7E:BE:B4:D2:F7:3C:11:CF:8C:CD:5A:DA:F4:B2:80:86:92:6D:BF:B9:71:83:A7:04:98` |
| `document-signer/passportemu-test-ds-my` | PassportEmu Test Document Signer MY (MY) | PassportEmu Test CSCA MY | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `AB:2D:CD:4B:62:8E:A0:84:35:1F:25:2A:92:83:E9:1D:BF:D9:2D:29:85:2C:5C:67:3B:BA:26:A8:8A:8A:CC:56` |
| `csca/passportemu-test-csca-nl` | PassportEmu Test CSCA NL (NL) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `73:F2:EA:D9:46:8D:1B:EA:F7:89:2A:98:C3:C0:6C:4E:C9:79:6D:EA:86:88:48:E7:A3:1F:6B:AF:69:EE:E6:B8` |
| `document-signer/passportemu-test-ds-nl-id` | PassportEmu Test Document Signer NL ID documents (NL) | PassportEmu Test CSCA NL | EC P-256 | 2026-11-16 to 2036-11-15 | `I`, `ID` | `4D:A8:5C:DF:E5:A6:B7:70:14:89:32:2A:D0:C6:8B:D5:93:30:1A:87:E3:B8:C5:43:BA:7C:28:E8:42:1B:0C:8B` |
| `document-signer/passportemu-test-ds-nl-rsa` | PassportEmu Test Document Signer NL RSA (NL) | PassportEmu Test CSCA NL | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `28:A7:0A:B8:CD:62:94:93:94:99:56:18:DD:B8:8E:C0:AB:EB:FD:AD:E5:79:80:55:29:C7:A9:77:A1:F3:FE:FB` |
| `document-signer/passportemu-test-ds-nl` | PassportEmu Test Document Signer NL (NL) | PassportEmu Test CSCA NL | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `C7:FB:13:1D:31:1A:65:C1:05:CB:C0:51:FE:CE:D3:EC:D3:6D:26:E7:16:63:B3:44:64:CD:46:2A:E6:04:28:8F` |
| `csca/passportemu-test-csca-nz` | PassportEmu Test CSCA NZ (NZ) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `B3:3A:9D:1F:FF:45:F6:AD:C0:51:FB:F7:98:AB:B8:42:D3:16:8B:DC:31:6A:22:BF:2B:80:82:AC:4D:90:53:53` |
| `document-signer/passportemu-test-ds-nz` | PassportEmu Test Document Signer NZ (NZ) | PassportEmu Test CSCA NZ | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `4D:F1:3B:11:7B:CF:40:5A:F4:E1:2E:DF:19:EC:51:9F:11:E1:9C:75:D8:B0:F1:DE:33:13:30:C8:A5:E6:92:61` |
| `csca/passportemu-test-csca-ph` | PassportEmu Test CSCA PH (PH) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `E8:66:F3:C3:E0:AF:1E:9C:CE:06:8F:90:5E:91:DF:4B:40:19:A4:B0:05:AF:E7:9A:EF:67:79:D3:91:A7:A2:25` |
| `document-signer/passportemu-test-ds-ph` | PassportEmu Test Document Signer PH (PH) | PassportEmu Test CSCA PH | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `99:EC:0D:16:AA:97:67:26:11:86:93:15:65:33:DC:8B:66:31:CF:AE:34:95:46:5A:A6:4A:70:FC:D9:EF:EC:20` |
| `csca/passportemu-test-csca-pt` | PassportEmu Test CSCA PT (PT) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `F0:20:1A:05:4A:03:51:5C:04:C1:1B:F8:7E:F1:82:AB:9E:C7:7D:01:29:61:B9:47:0A:88:80:69:32:25:1A:F6` |
| `document-signer/passportemu-test-ds-pt` | PassportEmu Test Document Signer PT (PT) | PassportEmu Test CSCA PT | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `EC:26:28:95:7E:76:E4:81:73:0A:22:97:95:84:89:07:9D:74:C8:6B:47:28:A5:31:1C:D7:8A:4C:2B:96:CB:17` |
| `csca/passportemu-test-csca-ru` | PassportEmu Test CSCA RU (RU) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `E2:AA:EE:7E:6E:76:9A:48:71:96:0F:A8:62:F4:28:71:2C:E2:CE:63:6A:13:B2:24:49:C7:6A:A6:05:1D:08:F0` |
| `document-signer/passportemu-test-ds-ru` | PassportEmu Test Document Signer RU (RU) | PassportEmu Test CSCA RU | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `4B:B5:B5:BA:24:2C:E4:53:48:FC:24:15:CB:06:DC:9D:CB:13:C0:AC:37:25:59:E5:ED:AB:1A:A4:CB:E9:49:CA` |
| `csca/passportemu-test-csca-sg` | PassportEmu Test CSCA SG (SG) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `BF:DC:54:C9:AD:56:12:DA:AC:7D:81:D3:68:6B:9C:10:28:15:F4:C7:B6:9F:00:04:55:F1:54:F9:34:1A:3E:89` |
| `document-signer/passportemu-test-ds-sg` | PassportEmu Test Document Signer SG (SG) | PassportEmu Test CSCA SG | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `D7:A4:D2:49:C8:48:03:73:C6:05:5F:D7:8B:92:A3:B2:F7:4D:0A:9B:0D:8A:59:AD:82:2A:B6:16:C9:7E:61:EA` |
| `csca/passportemu-test-csca-tw` | PassportEmu Test CSCA TW (TW) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `4C:F3:E3:C5:8F:D0:A4:18:94:67:D7:D8:6B:DD:0A:D7:5B:5E:0C:0D:B3:E4:C9:ED:73:67:1E:AF:41:C9:77:98` |
| `document-signer/passportemu-test-ds-tw` | PassportEmu Test Document Signer TW (TW) | PassportEmu Test CSCA TW | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `B5:AF:EB:8F:23:4A:CF:2C:CA:D9:25:F9:5C:9C:CC:00:CD:9C:ED:54:2B:0D:F7:94:4D:C7:0C:09:A4:B8:63:A7` |
| `csca/passportemu-test-csca-us` | PassportEmu Test CSCA US (US) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `C2:31:55:2E:63:E8:B7:9A:54:E0:D1:9E:F1:B8:35:ED:7B:5C:79:82:10:51:BE:E9:AC:D6:6D:1C:D2:8E:27:6A` |
| `document-signer/passportemu-test-ds-us-rsa` | PassportEmu Test Document Signer US RSA (US) | PassportEmu Test CSCA US | RSA 2048 | 2026-11-16 to 2036-11-15 | `P` | `89:DC:FD:37:13:B6:6D:D6:21:19:0D:15:23:16:32:03:0A:8A:29:B8:B5:E0:D3:F4:3C:91:D3:D1:2D:63:8E:F5` |
| `document-signer/passportemu-test-ds-us` | PassportEmu Test Document Signer US (US) | PassportEmu Test CSCA US | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `B6:85:35:D7:00:EA:1A:54:00:B4:82:36:5D:9A:11:23:C1:D4:2A:BF:52:95:4F:2F:D9:65:3B:94:1B:B4:B1:50` |
| `csca/passportemu-test-csca-xk` | PassportEmu Test CSCA XK (XK) | self-signed | EC P-256 | 2026-11-16 to 2046-11-16 |  | `4F:DE:41:CF:E1:20:E5:E7:6F:2D:58:04:BD:44:72:26:62:3D:36:1C:53:AD:E5:CF:31:28:4D:62:2C:4D:EA:8E` |
| `document-signer/passportemu-test-ds-xk` | PassportEmu Test Document Signer XK (XK) | PassportEmu Test CSCA XK | EC P-256 | 2026-11-16 to 2036-11-15 | `P` | `32:ED:A5:BF:AC:70:A0:05:0E:D5:52:43:89:39:96:DF:6E:35:EA:DF:0F:C4:DB:F4:5F:C9:9E:90:78:22:F5:FB` |

### Key choices

CSCA keys do not establish Document Signer, SOD, Active Authentication, Chip Authentication or PACE algorithms. Most
configured suites are ASSUMED; [profile evidence and generation boundaries](../docs/chip-profile-evidence.md) explain
the specific supported claims. These are emulator trust anchors and signers, not real issuer certificates.

- The Document Signer key types of AT, FI (ID card), SG, IDN, MY, NZ, RU, PH and CN come from the EF.SODs of real
  documents parsed from the gmrtd repository's samples. Finland's passport signer, Taiwan, Portugal, Hong Kong and
  Kosovo are EC P-256 stand-ins, because their real signer key is unknown.
- Belgium's RSA 2048 signer follows the 2008 study's Table 2; Canada's is an RSA stand-in (the issuer gives RSA-PSS
  examples). France, Spain, Italy, Switzerland and Australia's first signer are EC P-256 stand-ins.
- The `-rsa` signers sign SHA256withRSA (PKCS#1 v1.5) and `-p384` signs SHA384withECDSA on brainpoolP384r1.
- The Russian sample's SOD is signed with ECDSA and a SHA-1 digest, so that profile's SOD digest is SHA-1 on an
  EC P-256 key. The app does not generate RSASSA-PSS signatures or China's `id-data` content type, so profiles for
  documents that use them sign PKCS#1 v1.5 and `ldsSecurityObject` instead.
- Indonesia is `idn` in file names and CNs (its certificates say `C=ID`), because `passportemu-test-ds-id` is the
  generic ID card signer. Kosovo's certificates say `XK`, the user-assigned code, because its ICAO code RKS is not
  an ISO 3166 state.

## Using it elsewhere

- **Passive authentication**: add `csca/passportemu-test-csca.cert.pem`, and the country CSCAs of the profiles you
  use, to the verifier's CSCA trust store (or test master list). The Document Signer certificate is inside each
  EF.SOD, so it needs no installation.
- **Check a chain**: `openssl verify -CAfile csca/passportemu-test-csca-de.cert.pem document-signer/passportemu-test-ds-de.cert.pem`
- **Sign test SODs in other tools**: use a Document Signer's PEM key and certificate. A tool that wants PKCS#12 can
  have one made with `openssl pkcs12 -export -inkey document-signer/passportemu-test-ds.key.pem -in
  document-signer/passportemu-test-ds.cert.pem -certfile csca/passportemu-test-csca.cert.pem -out ds.p12`.

## Regenerating

`./generate.sh` (OpenSSL 3.4 or later) only creates what's missing, so it's safe to run to add a certificate.
`FORCE=1 ./generate.sh` replaces every key and certificate. That breaks every environment that trusts the current
CSCAs, so only do it deliberately (for example before the Document Signers expire in 2036), then regenerate the
tables above.
