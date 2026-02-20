# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- Block-level fact isolation. Public facts (`:public/` namespace) propagate
  across blocks. Private facts (`:private/` namespace) are scoped to their
  block. Private facts that contribute to derivations are redacted in proof
  trees and provenance records.