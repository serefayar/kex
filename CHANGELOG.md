# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- Origin-aware block scoping inspired by Biscuit's trusted origins model.
  Only the authority block's (block-index 0) public facts form the global scope.
  Delegated blocks can read global facts but cannot extend them.
  Attenuation can only restrict capabilities, never expand them.
- Block-index annotation: `evaluate` tags each block with its position
  so the engine distinguishes authority from delegated blocks.
- Comprehensive block isolation tests (9 tests covering propagation,
  local scope, escalation prevention, and proof redaction).

### Changed
- `eval-token` uses origin-aware propagation. Authority block's public facts
  and derivations propagate globally. Delegated blocks' facts and derivations
  are local-only.
- `redact-if-private` renamed to `redact-if-non-public`. Now redacts all
  non-public facts (including unnamespaced) in proof trees, not just `:private/`.
  Sentinel value: `:redacted/non-public-fact`.
- `fire-rule` uses `keep` instead of `map` to prevent nil entries.
- `authority-block` / `delegated-block` share a `sign-block` helper.

### Fixed
- Unnamespaced facts (default private) were not redacted in proof trees.
- Delegated blocks could inject `:public/` facts into global scope (privilege escalation).
- `generate-keypair` calls in crypto tests missing `"Ed25519"` argument.
