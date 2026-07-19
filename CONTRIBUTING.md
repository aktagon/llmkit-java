# Contributing

Thank you for your interest in llmkit.

## This repository is a read-only mirror

This repo is generated from a private monorepo and force-pushed on every
release. **Pull requests opened here cannot be merged — they are overwritten by
the next sync.** Please do not send code changes to this repo.

## How to contribute

- **Bugs and feature requests:** open an issue on this repository's issue
  tracker. A minimal reproduction (SDK, version, and the failing call) helps
  most.
- **Code patches:** propose them against the private source by emailing
  **christian@aktagon.com**. Include the diff or a description of the change and
  the motivation.
- **Security issues:** do not open a public issue — follow `SECURITY.md`.

## Notes for context

- llmkit ships four SDKs (Go, TypeScript, Python, Rust) that share a single
  provider and capability model. A change to the public surface usually needs to
  hold across all four, so cross-SDK impact is part of the review.
- Each SDK follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
  and records changes in `CHANGELOG.md`.
