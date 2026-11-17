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

## Provenance and licence

- **Provenance.** The raster logo (`passportemu-logo-source.png`) and the mascot and launcher images derived from it
  were generated with an AI image-generation tool, from the prompt in `prompt.txt`, and then selected and exported by
  the maintainer. The one-color mark (`../launcher/ic_launcher.svg`) was drawn by hand from the same shapes.
- **Licence.** The artwork is licensed under the same terms as the rest of the project, GNU GPL version 3 or (at your
  option) any later version. See the [LICENSE](../../LICENSE).
- **Copyright status is uncertain.** In some jurisdictions, images produced by a generative tool may not be
  protected by copyright at all, or only in part, and the position differs from country to country and is still
  changing. The licence above applies to whatever rights exist. It is not a promise that any exist.
- **No trademark check.** The name "PassportEmu" and the logo have not been searched or cleared against existing
  trademarks. Nothing here grants trademark rights, and nothing here promises that the name or mark is free to use in
  every country. If you fork the project and distribute it widely, consider your own name and mark.
