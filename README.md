# launcher-build

A branch of its own for building the Breeze Launcher installers while the
private repository's Actions are unavailable. It shares no history with
`main` and holds no launcher source at its tip: the source is pushed in the
commit before the one that runs, the workflow restores it from there, and the
branch is emptied to this README when the build is done.
