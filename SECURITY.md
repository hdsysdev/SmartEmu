# Security policy

## Reporting a vulnerability

Please report security problems **privately**, not in a public issue or pull request.

Use GitHub's private vulnerability reporting: open the repository's
[Security tab](https://github.com/hdsysdev/PassportEmu/security) and choose **Report a vulnerability**. If that
option is not available, open a public issue that says only that you have a security report to make and asks the
maintainer (`hdsysdev`) to enable a private channel. Do not put details in that issue.

Include what you found, the version or commit, the steps to reproduce it and the effect you expect. This is a
spare-time project run by one person: expect an acknowledgement within about a week, and a fix or a decision on a
best-effort basis. Please allow reasonable time before you publish details. Credit is given on request.

**Never attach real document data to a report.** That means no MRZ, no document or personal number, no CAN, no photo
and no raw chip dump or APDU log from a real document. Use the emulator's own synthetic data to show a problem.

## What is in scope

- The app: for example, a way for another app or a website to read or change the emulated data or the saved history
  without the user's action, a way to make the app write entered data where it should not go (logs, backups, shared
  files), or a flaw in the handling of reader commands that crashes the phone's NFC service.
- Capture mode in developer builds (built with `-Ppassportemu.captureMode=true`): any path by which data from a real
  document, such as an MRZ, a CAN, a UID or a data group, could be saved, logged, backed up or shared.
- The redaction lint and the capture-intake tooling, where a record that should be rejected is accepted.
- The build and CI configuration, where it could leak secrets or publish something unintended.

## What is not a vulnerability

- **The keys in [`test-pki/`](./test-pki/README.md) are public on purpose.** They sign test documents only, they
  protect nothing, and the README prints the password of the PKCS#12 file. Reports about them, including secret
  scanner alerts, will be closed. Do not trust those certificates in a production verifier; that is a deployment
  mistake, not a flaw in this project.
- That the emulated documents are not issued by a real authority. That is the design: they cannot pass passive
  authentication against real CSCA certificates.
- That the emulator's answers differ from a real chip's. Profile accuracy bugs are normal bugs: open an issue.
- That the chip answers while the phone is locked. This is documented under Limits in the README.
- Weaknesses in the ICAO 9303 protocols themselves, or in the libraries listed in
  [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md): report those to their projects.

## Supported versions

Only the latest release receives fixes.
