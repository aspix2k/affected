# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-08-07

### Added

- Run unit tests only for modules whose files changed.
- Check that modules directly consuming a changed public API still compile.
- Toolbar button with a live count of affected modules, disabled when nothing changed.
- Toggle to skip the consumer compilation check and run tests only.
- Base branch detection: configured branch, then `develop`, `main`, `master`.
- English and Russian user interface.
