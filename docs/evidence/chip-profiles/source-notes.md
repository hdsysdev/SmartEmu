# Authored notes on primary profile sources

Checked 2026-09-30. These are bounded summaries written for PassportEmu, not copies of the publications. Full downloaded sources are outside the repository in `/tmp/passportemu-source-cache`. The [manifest](manifest.json) records their URLs, hashes and locators; [profile evidence](../../chip-profile-evidence.md) maps findings to presets and assumptions.

## Richter, Mostowski and Poll, 2008

[Fingerprinting Passports](https://cs.ru.nl/E.Poll/papers/nluug.pdf) reports firsthand experiments on passports from ten countries. Sections 1–3, pages 1–5 support BAC for the sampled French, Italian and Spanish passports. A study year does not identify an introduction date or current production suite.

Page 5's paragraph below Table 1 distinguishes a correctly sized EXTERNAL AUTHENTICATE request (`Lc=40`) from the table's malformed request: the Spanish sample returns `6300`, the German sample `6985`. The malformed probe returns `6700` for both. This distinction is necessary for the emulator's response handling. The Dutch `6982` entry is for INTERNAL AUTHENTICATE; it does not establish the configured pre-challenge EXTERNAL AUTHENTICATE response. Malformed-command failures do not prove AA absence.

## Avoine, Kalach and Quisquater, 2008

[ePassport: Securing International Contacts with Contactless Chips](https://sites.uclouvain.be/security/download/papers/AvoineKQ-2008-fc.pdf) describes the authors' Belgian passport investigation. Section 3.3 (PDF page 10) separates the mid-2006 BAC generation from the preceding generation without BAC. Section 4 (PDF page 11) discusses DG7, DG11 and DG12; PassportEmu does not generate DG7.

Table 2 (PDF page 14) separately identifies RSA-1024 for AA and RSA-2048 for the document signer, and reports SHA-1 signature hashing. The country authority's key is not the document signer's key. Table 3's recommendations are not measured issuer settings. The paper does not resolve the emulator's chosen SOD padding or separate LDS digest.

## Vaudenay and Vuagnoux, Eurocrypt 2007

[E-Passport Survey](https://www.iacr.org/conferences/eurocrypt2007/slides/rumpt05.pdf), slide 7, describes one sample per studied country. Slides 10 and 12 report BAC present and AA absent for the Swiss sample. This supports a historical sample profile, not every Swiss passport generation. Entries marked “TBC” elsewhere were not used as findings.

## Government of Canada, 2014 backgrounders

[Technical information about the Canadian ePassport](https://www.canada.ca/en/news/archive/2014/05/technical-information-about-canadian-epassport.html) documents BAC under “Unauthorized reading: Basic access control”. The second paragraph under “Cloning: Active authentication” states AA is implemented, but supplies no AA key type. The Canadian preset therefore enables AA with an ASSUMED ECDSA P-256 key.

Under “Tampering and inauthenticity: Passive authentication”, RSA-PSS-2048 and SHA-256 are examples, not an exhaustive statement of required encoded algorithms. The emulator's RSA-2048/SHA-256 PKCS#1 v1.5 signature remains an ASSUMED stand-in. Neither this example nor a CSCA public key specifies AA or PACE.

[History of the ePassport](https://www.canada.ca/en/news/archive/2014/05/history-epassport.html), final paragraph under “The ePassport project and the User Fees Act”, places the adult ten-year option at 1 July 2013. Together these issuer pages scope the preset to that rollout, not the redesigned 2023 passport.

## Policía Nacional reader documentation

[Official DNIe portal reader page](https://www.dnielectronico.es/PortalDNIe/PRF1_Cons02.action?id_menu=20&pag=REF_035), “Ejemplo DNIe Lectura Datos”, explicitly includes third-generation passports using PACE. It does not identify a passport introduction date, curve, mapping or cipher. The preset's PACE parameters, BAC fallback, CA/TA and optional data remain ASSUMED; card-specific algorithms are not extrapolated to passports.

## Australian official audit

The separately retained [ANAO notes](anao-australia-2012-notes.md) cover M/N generation dates, BAC, and N's introduction of AA. The AA algorithm is unspecified. Those findings are not extended to P or R Series.
