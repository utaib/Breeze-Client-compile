# Breeze Client 1.1.0_test_11

Eleventh testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_11** (Config, About); the splash reads
`v1.1.0-test.11`. It is 1.1.0_test_10 plus one fix, and test_10's notes
still apply (`docs/test-builds/1.1.0_test_10.md`).

Installs over 1.0.38 or any earlier 1.1.0 test build and keeps accounts and
settings. Unsigned: on Windows, More info, Run anyway; on macOS, right
click, Open.

## What to test

1. **A Paper server.** Hosting, New server, Software: Paper (plugins), any
   recent version, Create. It used to fail with "Paper has no build"; now
   it downloads the newest stable Paper build and checks it against
   PaperMC's own checksum. Start it and join it like the others.
2. Everything in test_10's list.

## What changed since 1.1.0_test_10

- Paper servers can be made: PaperMC's build list was read wrongly, so no
  build was ever found. The server jar is now checked against the maker's
  hash (PaperMC's SHA-256 for Paper, Mojang's SHA-1 for Vanilla).

## Known limits

- The same as test_10.

## Build results

Filled in after the build run.

## Reporting

Say "1.1.0_test_11" with what you did, what you expected and what happened.
