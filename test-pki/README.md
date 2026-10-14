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
| `csca/smartemu-test-csca-<cc>.*`, `document-signer/smartemu-test-ds-<cc>*.*` | Test CSCAs and Document Signers for country chip profiles (DE, NL, GB, US, FR, BE, ES, IT, CH, CA, AU, AT, FI, SG, TW, PT, IDN, HK, MY, NZ, RU, PH, CN); see [Country test PKIs](#country-test-pkis) |
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
retain `O=SmartEmu`; new FR/BE/ES/IT/CH/CA/AU and later subjects use `O=PassportEmu`. Legacy resource basenames
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

Three more Document Signers chain to the existing DE, US and AU CSCAs above (no CSCA changed). The signer key type
is independent of the CSCA's. Each starts when its CSCA does, like the first signer of that country:

| Certificate | Subject CN (C) | Key | Valid | Document types | SHA-256 fingerprint |
|---|---|---|---|---|---|
| `document-signer/smartemu-test-ds-us-rsa` | SmartEmu TEST Document Signer US RSA (US), issued by SmartEmu Test CSCA US | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `C8:6B:B2:6D:43:9C:D7:12:E9:9A:51:EA:B5:72:5B:F3:74:9A:34:A0:1B:09:B8:B9:ED:08:49:39:7B:61:4A:0E` |
| `document-signer/smartemu-test-ds-au-rsa` | PassportEmu TEST Document Signer AU RSA (AU), issued by PassportEmu TEST CSCA AU | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `60:E0:2C:4A:0E:78:89:E1:16:BD:AC:4C:84:71:84:FB:EF:4C:04:13:42:ED:FC:C2:8A:72:96:B7:E6:A5:E8:69` |
| `document-signer/smartemu-test-ds-de-p384` | SmartEmu TEST Document Signer DE P-384 (DE), issued by SmartEmu Test CSCA DE | EC brainpoolP384r1 | 2026-09-30 to 2036-09-29 | `P` | `6F:5E:AF:7F:C4:17:50:B3:67:1D:52:70:70:E8:1C:CF:E9:DC:BC:CE:91:B8:92:11:74:6D:4D:79:0D:82:C8:07` |

All of them, the three above included, are signed with ecdsa-with-SHA256 by their matching test CSCA. The Dutch RSA signer is for the 2006 passport
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

### Later country PKIs

Twelve more country CSCAs and thirteen Document Signers were added for further passport and ID card profiles,
generated by one `country_pki` call per country, which creates the CSCA once and issues every signer of that
country. All of them start on 2026-09-30 22:47:57 UTC (the same time as the Canadian ones), not on the day they were
generated, so that a reader whose clock is a little behind still accepts them: the CSCA runs 20 years and each
signer 3652 days from the CSCA's start. The CSCAs are EC P-256 test trust anchors and sign with ecdsa-with-SHA256,
whatever the signer's key type. Indonesia is `idn` in file names and CNs (its certificates say `C=ID`), because
`smartemu-test-ds-id` is the generic ID card signer. France gets only an extra signer under its existing CSCA.

The Document Signer key types come from the EF.SODs of real documents parsed from the gmrtd repository, except the
marked stand-ins, whose real signer key is unknown: the Finnish passport signer, Taiwan, Portugal and Hong Kong.
The Russian sample's SOD is signed with ECDSA and a SHA-1 digest, so that profile's SOD digest is SHA-1 on an
EC P-256 key. The app does not generate RSASSA-PSS signatures or China's `id-data` content type, so profiles for
documents that use them sign PKCS#1 v1.5 and `ldsSecurityObject` instead.

| Certificate | Subject CN (C) | Key | Valid | Document types | SHA-256 fingerprint |
|---|---|---|---|---|---|
| `csca/smartemu-test-csca-at` | PassportEmu TEST CSCA AT (AT) | EC P-256 | 2026-09-30 to 2046-09-30 | | `80:FE:1C:A2:86:9E:2B:2B:55:E2:E4:71:85:3A:DD:D4:07:E4:AA:69:72:B2:F8:0B:5F:37:71:AD:74:02:E3:28` |
| `document-signer/smartemu-test-ds-at` | PassportEmu TEST Document Signer AT (AT) | EC brainpoolP256r1 | 2026-09-30 to 2036-09-29 | `P` | `D9:5D:7E:DC:90:B0:96:2A:A2:FD:82:48:4F:D2:5E:2E:F0:05:93:1D:E0:E2:10:32:D6:06:1E:E2:CB:58:F3:87` |
| `csca/smartemu-test-csca-fi` | PassportEmu TEST CSCA FI (FI) | EC P-256 | 2026-09-30 to 2046-09-30 | | `8B:4D:9B:7C:40:31:69:18:A9:68:A1:B7:84:52:70:D8:A8:3C:4E:6E:3C:6A:28:51:5B:BB:06:B3:0F:FC:43:0D` |
| `document-signer/smartemu-test-ds-fi` | PassportEmu TEST Document Signer FI (FI) | EC P-256 (stand-in) | 2026-09-30 to 2036-09-29 | `P` | `4B:39:06:6F:2F:4A:2D:33:CC:81:B6:68:AB:F0:50:47:DD:9A:5C:D0:00:57:28:6E:FF:38:B3:01:47:33:96:2E` |
| `document-signer/smartemu-test-ds-fi-id` | PassportEmu TEST Document Signer FI ID documents (FI) | EC brainpoolP512r1 | 2026-09-30 to 2036-09-29 | `ID`, `IR` | `00:E0:54:4A:DC:23:60:64:A1:EB:70:F3:59:B1:93:9A:A8:DF:A0:7B:21:1F:01:2D:64:A4:ED:DB:81:B5:B0:2A` |
| `csca/smartemu-test-csca-sg` | PassportEmu TEST CSCA SG (SG) | EC P-256 | 2026-09-30 to 2046-09-30 | | `6C:F2:98:FB:C9:08:52:E9:97:5B:29:80:7E:D0:6F:E2:9D:D6:BD:11:79:35:4B:B5:94:28:CF:66:0B:07:2F:41` |
| `document-signer/smartemu-test-ds-sg` | PassportEmu TEST Document Signer SG (SG) | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `F8:7E:00:9C:43:0B:AF:35:C8:46:30:C0:A4:80:5F:6D:73:ED:76:39:81:CA:5A:74:C2:85:AC:4D:18:04:EF:17` |
| `csca/smartemu-test-csca-tw` | PassportEmu TEST CSCA TW (TW) | EC P-256 | 2026-09-30 to 2046-09-30 | | `29:1F:C8:6C:27:CB:19:A0:41:E8:EB:86:1F:64:EA:89:61:9B:73:89:5F:29:FC:B5:93:D1:2F:59:0A:8B:D0:A7` |
| `document-signer/smartemu-test-ds-tw` | PassportEmu TEST Document Signer TW (TW) | EC P-256 (stand-in) | 2026-09-30 to 2036-09-29 | `P` | `D7:F4:46:68:A5:52:F3:3B:78:B6:4E:F1:1C:9F:64:2A:EF:55:86:B1:2B:5B:F2:41:E4:0B:55:4D:E5:A6:F6:39` |
| `csca/smartemu-test-csca-pt` | PassportEmu TEST CSCA PT (PT) | EC P-256 | 2026-09-30 to 2046-09-30 | | `DF:4F:90:E7:52:6C:CD:E6:31:AD:4D:12:15:88:7B:BA:31:C7:D7:72:5C:3D:05:E0:1B:96:D3:11:BB:45:9B:24` |
| `document-signer/smartemu-test-ds-pt` | PassportEmu TEST Document Signer PT (PT) | EC P-256 (stand-in) | 2026-09-30 to 2036-09-29 | `P` | `26:67:A0:33:68:34:F2:A2:4F:10:85:5D:7D:EB:17:A2:7A:F5:A0:00:39:CC:49:D0:81:5D:AD:D2:59:14:E9:D9` |
| `csca/smartemu-test-csca-idn` | PassportEmu TEST CSCA IDN (ID) | EC P-256 | 2026-09-30 to 2046-09-30 | | `95:99:AB:30:8E:27:0C:2D:AA:ED:04:D5:94:13:B1:9A:3F:8C:77:10:8C:E9:CE:BB:1E:C9:D0:03:78:17:5A:1D` |
| `document-signer/smartemu-test-ds-idn` | PassportEmu TEST Document Signer IDN (ID) | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `86:58:7C:FC:DF:CA:1B:88:1A:9F:1C:01:E9:68:B3:62:1A:5D:86:9D:7E:94:E8:02:1D:26:A4:A3:8F:1F:C7:F8` |
| `csca/smartemu-test-csca-hk` | PassportEmu TEST CSCA HK (HK) | EC P-256 | 2026-09-30 to 2046-09-30 | | `A4:D7:E5:B2:01:F2:B5:C5:D9:14:8F:CB:C5:2F:C5:51:35:0F:EB:F7:88:6C:C2:24:5D:AD:61:8E:D5:A3:5F:F8` |
| `document-signer/smartemu-test-ds-hk` | PassportEmu TEST Document Signer HK (HK) | EC P-256 (stand-in) | 2026-09-30 to 2036-09-29 | `P` | `2B:7A:D5:1D:BB:C4:A9:9E:6D:73:9E:A5:D5:73:09:8A:9B:8C:DB:37:90:D4:F5:DC:9C:F6:9E:AC:F3:38:F7:45` |
| `csca/smartemu-test-csca-my` | PassportEmu TEST CSCA MY (MY) | EC P-256 | 2026-09-30 to 2046-09-30 | | `7F:69:F9:2F:1C:00:95:58:6F:E4:7E:21:7F:AE:5E:4D:2C:CB:65:44:E6:00:66:8F:8C:04:AC:82:86:7E:31:43` |
| `document-signer/smartemu-test-ds-my` | PassportEmu TEST Document Signer MY (MY) | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `0D:46:28:B4:D4:DB:75:59:73:35:CC:83:37:59:88:CD:16:D3:76:B1:24:4C:AD:5E:B4:D1:4E:0E:12:4C:C6:8D` |
| `csca/smartemu-test-csca-nz` | PassportEmu TEST CSCA NZ (NZ) | EC P-256 | 2026-09-30 to 2046-09-30 | | `82:B6:7B:49:F6:45:F1:11:10:EC:B9:5E:B1:BC:25:D2:5C:F7:F1:FD:B2:34:E9:F2:CE:D4:73:35:89:DF:BD:AF` |
| `document-signer/smartemu-test-ds-nz` | PassportEmu TEST Document Signer NZ (NZ) | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `42:B1:F2:EC:45:6D:43:6E:82:65:5D:D6:DB:60:E3:4C:A5:53:5F:6E:40:CA:AE:DE:BD:0A:75:D0:53:20:60:DE` |
| `csca/smartemu-test-csca-ru` | PassportEmu TEST CSCA RU (RU) | EC P-256 | 2026-09-30 to 2046-09-30 | | `62:EB:E1:2C:E0:11:AB:EA:96:EF:A5:E7:E0:E5:B2:0F:1B:CB:7E:0D:A9:7F:C8:DA:C6:05:6B:E8:EA:DB:FD:A2` |
| `document-signer/smartemu-test-ds-ru` | PassportEmu TEST Document Signer RU (RU) | EC P-256 | 2026-09-30 to 2036-09-29 | `P` | `5D:39:39:92:96:01:3D:B5:A1:1C:94:60:C4:68:AA:64:9B:FB:8A:63:86:0C:10:B3:3E:FA:DF:53:B6:C1:37:BD` |
| `csca/smartemu-test-csca-ph` | PassportEmu TEST CSCA PH (PH) | EC P-256 | 2026-09-30 to 2046-09-30 | | `CF:30:21:60:FE:A1:39:F4:92:EA:CA:51:EC:AA:B4:D8:5B:F7:C7:EB:7F:9B:45:4F:A9:98:15:14:A8:DC:4D:1F` |
| `document-signer/smartemu-test-ds-ph` | PassportEmu TEST Document Signer PH (PH) | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `B7:3E:B0:50:72:77:EC:EC:AD:CB:00:B2:67:15:10:D7:6E:77:4A:27:4F:E9:62:EC:77:1F:5B:04:3E:5A:63:3E` |
| `csca/smartemu-test-csca-cn` | PassportEmu TEST CSCA CN (CN) | EC P-256 | 2026-09-30 to 2046-09-30 | | `AE:E6:53:A8:68:F0:8D:33:ED:2F:8D:6A:99:92:43:F6:2B:0C:47:2D:EE:E6:B9:2A:33:D2:E4:DF:81:2E:A5:80` |
| `document-signer/smartemu-test-ds-cn` | PassportEmu TEST Document Signer CN (CN) | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `CE:64:C0:D4:4E:C3:DF:16:0A:9C:74:98:80:16:EC:1E:35:47:D3:61:8E:81:03:EE:CC:DA:85:64:FD:CA:70:8E` |

The French RSA signer chains to the existing `PassportEmu TEST CSCA FR` (no CSCA changed) and starts when it does:

| Certificate | Subject CN (C) | Key | Valid | Document types | SHA-256 fingerprint |
|---|---|---|---|---|---|
| `document-signer/smartemu-test-ds-fr-rsa` | PassportEmu TEST Document Signer FR RSA (FR), issued by PassportEmu TEST CSCA FR | RSA 2048 | 2026-09-30 to 2036-09-29 | `P` | `4C:AD:C4:DB:57:BA:29:4F:1E:95:A0:5A:30:0F:95:30:85:09:35:14:CA:C0:3B:DC:CF:A0:0E:1F:E8:06:54:5B` |

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
