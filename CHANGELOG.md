# Changelog

All notable changes to the Java SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **BREAKING — an unreported usage dimension or finish signal is now absent, not zero.**
  The six `Usage` dimensions (input, output, cache read, cache write, reasoning,
  cost) and the finish reason / finish message on every response container are
  now boxed `Long` / `Double` / `String`. Previously a provider that reported no
  cached tokens and one that never mentioned caching both produced `0`, and an
  unreported cost read as a free request.

  Migration: `resp.usage().input()` may be `null`; check before unboxing, and note that auto-unboxing a missing count now throws rather than yielding `0`.

  Aggregation across agent turns is **absorbing** — a total is reported only when
  every turn reported that dimension, so a partial sum is never presented as a
  total. Telemetry now exports a usage attribute when the value is *reported*,
  zero included, instead of when it is greater than zero.

### Fixed

- **Batch results are matched to prompts by request id, not by line order.**
  Providers return batch results in any order, and a failed request used to be
  dropped, so later answers moved up one place. Waiting on a batch now returns
  one response per prompt, at the prompt's index. A failed request keeps its
  slot with empty text, the provider's result status as the finish reason
  (`errored`, `expired`, `canceled`, or `error`) and the provider's error
  message as the finish message. A request with no result reads finish reason
  `missing`. Results for request ids this SDK did not assign follow in file
  order.

## [1.0.0] — 2026-07-19

First release. Java 17 floor, one dependency (Gson) over the JDK's own
`java.net.http` and `javax.crypto`. Synchronous API mirroring the Go SDK.
Distributed via Maven Central. Born on the post-ADR-064 API so it enters the
pack at parity with the other SDKs' v2.0.0/v3.0.0 line.

### Added

- Chat completion — `c.text().system(...).prompt(...)`, with `stream`, `agent`
  (tool loop), and `batch` execution modes on the `Text` builder. `batch(...)`
  returns a `BatchHandle`; `handle.await()` resolves the ordered results.
- Media capabilities — image generation, video generation (async handle),
  speech (TTS) and music generation, and transcription (STT, async handle).
- File upload — `c.upload().path(...)` / `.bytes(...)` returns a `File` id to
  attach to later requests.
- Prompt caching, request/response middleware (observation + veto), and
  opt-in OpenTelemetry (OTLP/HTTP) telemetry with a typed `error.type`.
- Model catalogue — `c.models()` / `c.providers()` (compiled-in metadata plus
  a `live()` path), and the keyless `*GenConfig` accessors.
- Typed provider identity and `c.supports(...)` capability checks.
