# PassportEmu artwork

The logo is an emu holding a passport, drawn as a compact navy, gold and cream mark. The same mark is used as the in-app mascot. The launcher has adaptive, round, legacy-density and one-color themed versions.

- `passportemu-logo-source.png`: selected transparent image generated with the built-in image tool.
- `passportemu-icon-preview.png`: shareable launcher preview.
- `../launcher/ic_launcher.svg`: hand-drawn one-color companion mark; `ic_launcher_monochrome.xml` uses the same paths for themed icons.
- `export-icons.swift`: reproduces the PNG size/layout exports on macOS with AppKit; it only trims transparent margins, scales and lays out the source. No model or API key is needed.
- `prompt.txt`: final image-generation prompt. Earlier detailed cartoon drafts were discarded after the logo-style correction.

Run from the project root:

```sh
swift composeApp/branding/export-icons.swift
```

The adaptive foreground includes safe-zone padding; do not apply a second inset. The welcome animation uses Compose’s animation clock, which follows the device animation scale.
