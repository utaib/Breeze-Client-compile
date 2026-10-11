# Breeze Client temporary build runner

This is a public CI-only repository used to run cross-platform packaging when the private repository's Actions runners are unavailable.

- The production source of truth is the private `utaib/Breeze-Client` repository.
- Do not commit launcher application source or secrets to this repository.
- Successful builds create an **unpublished draft release** with installers and SHA-256 metadata. Draft assets are not production releases.
- Before any installer is uploaded to the API, verify its source commit, version, checksum, per-platform install/launch smoke test, and the relevant Minecraft launch regression.
- The branch tip is kept free of temporary source snapshots. Earlier temporary snapshots may remain in Git history and must not be treated as proof that the private source is private.

The API is deployed manually by the owner. This repository never deploys the API or updates its production version manifest.
