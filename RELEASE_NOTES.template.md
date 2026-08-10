# PassPony (Android) {{VERSION}}

<!--
Fill this in before running `gh release create` (scripts/release.sh stops
before that step on purpose). Honest changelog, no marketing fluff -- per
docs/plan/P16-release.md item 4. A one- or two-line summary of what
changed since the last release, then a short bullet list if there's more
than one notable change. Delete this comment block before publishing.
-->

## Verification

- Content hash: `{{CONTENT_HASH}}`
- SHA-256 checksums: see `PassPonyAndroid-{{VERSION}}-SHA256SUMS.txt`
- Reproduce this build yourself: `tools/verify_repro.sh rebuild {{TAG}} PassPonyAndroid-{{VERSION}}-foss.apk`

See [docs/REPRODUCIBLE.md](docs/REPRODUCIBLE.md) for what "content hash" means and why it isn't a whole-file SHA-256.
