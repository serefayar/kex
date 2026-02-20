# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- Block-level fact isolation via namespace-based visibility.
  Facts marked `:public/` propagate to subsequent blocks.
  Facts marked `:private/` are scoped to their block and do not propagate.
  Privacy is the default; visibility must be explicitly declared.

### Changed
- `eval-token` now evaluates each block in its own scope. A block can only
  see public facts from previous blocks plus its own facts.
- `eval-token` now returns `:explain` for both valid and invalid decisions
  when `:explain?` is true. Returns `:no-checks` when no checks are present.
- Private facts that contribute to a derivation are redacted in the proof tree.
  Redacted entries appear as `{:type :fact :fact :redacted/private-fact :redacted? true}`.
