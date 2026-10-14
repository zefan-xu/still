## What changes for someone using Still

<!-- One or two sentences. This PR title becomes a line in the release notes. -->

## How it was checked

- [ ] `python3 scripts/test.py local` passes (tests, coverage and mutation floors, lint, builds)
- [ ] `python3 scripts/test.py device` passes on an API 29+ emulator, or the change cannot affect Android behavior
- [ ] New behavior has a test that fails without the change
- [ ] UI change: screenshots below, light and dark (`python3 scripts/test.py screenshots` refreshes the README set)
- [ ] Platform change: `python3 scripts/test.py live --class …` for the affected platform
