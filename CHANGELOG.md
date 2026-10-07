# Changelog

All notable changes to the Java SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.1] — 2026-10-07

### Changed
- The javadoc jar and the sources jar carry their documentation comments.
- The error for a chat protocol on a terminal other than prompt no longer ends with an internal reference.
- 1.1.0 was not published to Maven Central. 1.1.1 is the first Maven release after 1.0.0 and includes the 1.1.0 changes below.

## [1.1.0] — 2026-10-04

### Added

- `ResponseCodec.decodeResponse(provider, chatWireShape, body)` turns a provider's chat response body into a `Response`, the same way the client reads it. `ResponseCodec.encodeResponse(provider, chatWireShape, response)` writes a `Response` back into that provider's body shape. Both are pure functions: no client, no key, no network. `encodeResponse` throws for a field it cannot write back faithfully.

### Fixed

- Middleware now runs for speech generation and transcription requests, as the new `speech_generation` and `transcription` operations, and its pre phase can veto them. For transcription it runs on the sync and async calls, not on waits or polls.
- OpenAI Responses protocol: gpt-5 and o-series models now send the output-token cap as `max_output_tokens`. They sent `max_completion_tokens`, which the Responses API rejects with HTTP 400.
- Anthropic requests no longer send `anthropic-beta: files-api-2025-04-14`. The Files API left beta, and with that header an upload returned the older response shape, without `expires_at`.

## [1.0.0] — 2026-07-19

First release. Java 17 floor, one dependency (Gson) over the JDK's own
`java.net.http` and `javax.crypto`. Synchronous API mirroring the Go SDK.
Distributed via Maven Central. Born on the handle-based batch API so it enters the
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
