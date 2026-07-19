# llmkit (Java)

One Java API for Anthropic, OpenAI, Google, and 30+ other providers — including local models through Ollama and vLLM. Switch providers without rewriting your request.

Synchronous API on Java 17+, one dependency (Gson).

Also available for Go, TypeScript, Python, Rust, and Swift.

<p align="center">
  <img src="https://raw.githubusercontent.com/aktagon/llmkit-java/master/assets/logos/llmkit-languages.svg" alt="Go, TypeScript, Python, Rust, Swift, Java" height="26">
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/aktagon/llmkit-java/master/assets/logos/llmkit-providers.svg" alt="Anthropic, OpenAI, Google, and 26 more providers" height="26">
</p>

## Install

**Gradle** (`build.gradle.kts`):

```kotlin
dependencies {
    implementation("com.aktagon:llmkit:1.0.0")
}
```

**Maven** (`pom.xml`):

```xml
<dependency>
    <groupId>com.aktagon</groupId>
    <artifactId>llmkit</artifactId>
    <version>1.0.0</version>
</dependency>
```

Requires Java 17 or later. The only runtime dependency is Gson (no transitive dependencies).

Prefer to build from source? Clone and `./gradlew build` (the jar lands in `build/libs/`), or point a [Gradle composite build](https://docs.gradle.org/current/userguide/composite_builds.html) at the clone with `includeBuild("../llmkit-java")`.

## Quick Start

```java
import com.aktagon.llmkit.Client;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;

Client c = new Client(ProviderName.ANTHROPIC, System.getenv("ANTHROPIC_API_KEY"));
Response resp = c.text()
        .system("Be concise.")
        .temperature(0.3)
        .prompt("Why is the sky blue?");

System.out.println(resp.text());
System.out.println(resp.usage().input() + " input tokens");
```

The typed builder is the only public surface. One mental model — `client.<capability>().<chain>.<terminal>` — across every capability. Builders are immutable: chain methods return a fresh builder, so a shared `c.text().system(...)` prototype can be forked safely.

Runnable counterparts to the streaming, batch, music, and video blocks below live in [`ExampleSnippetsTest.java`](src/test/java/com/aktagon/llmkit/ExampleSnippetsTest.java) and run against canned transports on every build, so the call shapes shown here are guaranteed to execute against the real builder surface.

## Providers

Construct a client with any `ProviderName`:

```
AI21        ANTHROPIC   ASSEMBLYAI  AZURE       BEDROCK     CEREBRAS
COHERE      DEEPSEEK    DOUBAO      ERNIE       FIREWORKS   GOOGLE
GROK        GROQ        INWORLD     JAN         LLAMACPP    LMSTUDIO
MINIMAX     MISTRAL     MOONSHOT    OLLAMA      OPENAI      OPENROUTER
PERPLEXITY  PIXVERSE    QWEN        RECRAFT     SAMBANOVA   TOGETHER
VERTEX      VIDU        VLLM        WORKERSAI   YI          ZHIPU
```

```java
Client c = new Client(ProviderName.GOOGLE, System.getenv("GOOGLE_API_KEY"));
Client o = Client.openai(System.getenv("OPENAI_API_KEY")); // convenience factory
```

Four chat API shapes (OpenAI-compatible, Anthropic Messages, Google Generative AI, AWS Bedrock Converse), plus dedicated media providers for image, video, music, speech, and transcription. Bedrock auth uses SigV4; other providers use API-key auth.

## API

### Text — one-shot prompt

```java
Response resp = c.text()
        .system("You are helpful")
        .temperature(0.7)
        .maxTokens(200)
        .prompt("What is 2+2?");

resp.text();               // "4"
resp.usage().input();      // prompt tokens
resp.usage().output();     // completion tokens
resp.usage().cacheRead();  // tokens served from cache
resp.usage().cacheWrite(); // tokens written to cache (Anthropic explicit)
resp.usage().reasoning();  // internal reasoning tokens (OpenAI o-series, Gemini 2.5+)
```

Capability-scoped fields (`cacheRead`, `cacheWrite`, `reasoning`) are zero when the provider doesn't report them separately.

### Stream — callback + final response

The callback fires for each chunk; the terminal returns the final `Response` with token counts.

<!-- llmkit:include java/src/test/java/com/aktagon/llmkit/ExampleSnippetsTest.java#stream -->
```java
Response resp = client.text()
        .system("Be brief")
        .stream("Tell me a joke", System.out::print);

System.out.println();
System.out.println("Usage: " + resp.usage().input() + " in / "
        + resp.usage().output() + " out");
```

### Agent — tool loop

```java
import com.aktagon.llmkit.Tool;
import com.aktagon.llmkit.Json;

Tool add = new Tool(
        "add",
        "Add two numbers",
        Json.parse("{\"type\":\"object\",\"properties\":{"
                + "\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}}}"),
        args -> String.valueOf(args.get("a").getAsDouble() + args.get("b").getAsDouble()));

Agent bot = c.agent()
        .system("You are a calculator.")
        .addTool(add)
        .maxToolIterations(5);

Response resp = bot.prompt("What is 2+3?");
System.out.println(resp.text());
```

`Agent` is **stateful** — repeated `bot.prompt(...)` calls accumulate conversation history. Tool dispatch covers Anthropic `tool_use`, OpenAI `tool_calls`, Google `functionCall`, and Bedrock Converse `toolUse`.

### Image input (vision)

Attach an image to a text prompt with `.image(mime, bytes)`; it is sent as the provider's native image block (works on Anthropic, OpenAI, Google, and Bedrock). Bytes-based, so no filesystem is required:

```java
Response resp = c.text()
        .image("image/png", screenshotBytes)
        .prompt("Describe this screenshot in one sentence.");
```

Files uploaded through the Files API attach by id with `.file(id)` (see Upload below).

### Image — text-to-image and edit

Supports Google's Nano Banana 2 (`gemini-3.1-flash-image-preview`) and Pro (`gemini-3-pro-image-preview`); OpenAI's `gpt-image-2`, `gpt-image-1.5`, `gpt-image-1`, and `gpt-image-1-mini`; xAI's `grok-imagine-image-quality`; Google Cloud Vertex AI's Imagen 3 / Imagen 4; Recraft's raster and vector models.

```java
Client g = new Client(ProviderName.GOOGLE, System.getenv("GOOGLE_API_KEY"));
ImageResponse img = g.image()
        .model("gemini-3.1-flash-image-preview")
        .aspectRatio("16:9")
        .imageSize("2K")
        .generate("A nano banana dish, studio lighting");

Files.write(Path.of("out.png"), img.images().get(0).bytes());
```

For compositional editing, chain `.image(mime, bytes)` calls to pass reference images:

```java
g.image()
        .model("gemini-3.1-flash-image-preview")
        .image("image/png", personBytes)
        .image("image/png", outfitBytes)
        .generate("Generate the person wearing the outfit.");
```

Aspect ratios and sizes validate against a per-model whitelist before the HTTP request; empty whitelists pass through to the API boundary. Provider knobs are typed chain methods — `.quality(s)`, `.outputFormat(s)`, `.background(s)` (OpenAI gpt-image-\*), `.count(n)` (OpenAI + xAI). Calling a knob the bound provider doesn't support throws `ValidationException` immediately, no HTTP round-trip.

### Music — text-to-music

Generate audio from a text prompt. Decoded audio bytes come back on `resp.audio().get(0).bytes()`. Models that support vocals take lyrics via `.lyrics(...)` (use section tags like `[verse]`).

<!-- llmkit:include java/src/test/java/com/aktagon/llmkit/ExampleSnippetsTest.java#music -->
```java
MusicResponse resp = client.music()
        .model("lyria-002")
        .generate("a calm instrumental, warm piano and soft strings");

AudioData first = resp.audio().get(0);
System.out.println("got " + first.bytes().length + " bytes (" + first.mimeType() + ")");
```

Write `first.bytes()` to a file to play the clip.

| Provider | Model(s)                                      | Lyrics | Output     |
| -------- | --------------------------------------------- | ------ | ---------- |
| Vertex   | `lyria-002`                                   | no     | WAV (~30s) |
| Google   | `lyria-3-pro-preview`, `lyria-3-clip-preview` | yes    | MP3        |
| MiniMax  | `music-2.6`                                   | yes    | MP3        |

### Video — text-to-video

Video generation is asynchronous: `submit` returns a job immediately, and `job.await()` polls until it finishes. The result carries a temporary hosted URL on `resp.videos().get(0).url()` — download it yourself.

<!-- llmkit:include java/src/test/java/com/aktagon/llmkit/ExampleSnippetsTest.java#video -->
```java
VideoJob job = client.video()
        .model("grok-imagine-video")
        .submit("a slow cinematic drone shot flying over snow-capped"
                + " alpine peaks at golden hour");

VideoResponse resp = job.await();
VideoData first = resp.videos().get(0);
System.out.println("url=" + first.url() + " duration="
        + first.durationSeconds() + "s mime=" + first.mimeType());
```

`job.poll()` is the single-round-trip primitive — one normalized status (`RUNNING` / `SUCCEEDED` / `FAILED`) without blocking. Image-to-video seeds a start frame with `.image(mime, bytes)` before `submit`.

### Speech — text-to-speech

```java
Client i = new Client(ProviderName.INWORLD, System.getenv("INWORLD_API_KEY"));
SpeechResponse speech = i.speech()
        .model("inworld-tts-2")
        .voice("Dennis")
        .generate("Welcome aboard. Please stow your luggage.");

Files.write(Path.of("out.wav"), speech.audio().bytes());
```

OpenAI TTS (`gpt-4o-mini-tts`, `tts-1`) uses the same chain and returns raw audio bytes.

### Transcription — speech-to-text

OpenAI Whisper transcribes synchronously from inline bytes; AssemblyAI ingests a public URL and polls asynchronously. Both return the same `TranscriptionResponse` (full text plus timed segments).

```java
import com.aktagon.llmkit.Part;

// OpenAI — synchronous multipart upload
TranscriptionResponse t = c.transcription()
        .model("whisper-1")
        .transcribe(List.of(Part.audioBytes("audio/mpeg", audioBytes)));

// AssemblyAI — async submit + await
TranscriptionJob job = a.transcription()
        .submit(List.of(Part.audio("https://example.com/interview.mp3")));
TranscriptionResponse done = job.await();

for (TranscriptSegment s : done.segments()) {
    System.out.println(s.start() + "ms: " + s.text());
}
```

### Upload — path or bytes

```java
// from a path
File file = c.upload().path("./data.pdf").run();

// from bytes (filename required)
File file2 = c.upload()
        .bytes(buf)
        .filename("report.pdf")
        .mimeType("application/pdf")
        .run();

// attach to a later prompt by id
c.text().file(file.id()).prompt("Summarize the attached document.");
```

### Batches

<!-- llmkit:include java/src/test/java/com/aktagon/llmkit/ExampleSnippetsTest.java#batch -->
```java
BatchJob job = client.text()
        .system("Be brief")
        .batch(
                "Translate hello to French",
                "Translate hello to Spanish",
                "Translate hello to German");

List<Response> results = job.await();
results.forEach(r -> System.out.println(r.text()));
```

`batch(...)` queues the prompts and returns a `BatchJob` handle; `job.await()` blocks until completion and returns the parsed responses in prompt order, and `job.poll()` is the non-blocking single-status primitive. Both inline (Anthropic) and file-reference (OpenAI two-hop) flows are handled internally. A batch that exceeds the poll deadline throws `PollTimeoutException` — distinguishable from a provider failure (`JobFailedException`).

### Caching

```java
// Anthropic — explicit cache_control wrap of the system prompt.
c.text().system(longSystemPrompt).caching().prompt("...");

// OpenAI — automatic server-side caching (caching() is a hint; reads
// surface in resp.usage().cacheRead() regardless).
c.text().system(longSystemPrompt).caching().prompt("...");

// Google — pre-flight call creates a cachedContents resource, then the
// main call references it. Google requires ~1k+ tokens of system prompt.
c.text().system(bigSystemPrompt).caching().cacheTtl(7200).prompt("...");
```

The mode is provider-specific and inferred from the provider configuration. The default TTL for Google is 3600s; override it with `.cacheTtl(seconds)`.

### Model catalogue

```java
import com.aktagon.llmkit.Capability;

// 1. Compiled-in catalogue — synchronous, no HTTP.
List<ModelInfo> all = c.models().list();
Optional<ModelInfo> info = c.models().get("claude-opus-4-7");
List<ModelInfo> chat = c.models().withCapability(Capability.CHAT_COMPLETION).list();

// 2. Providers namespace — the bound provider's public metadata.
c.providers().list();

// 3. Live + scoped HTTP.
LiveResult live = c.models().live();                       // aggregated, with per-provider errors
List<ModelInfo> scoped = c.models().provider(ProviderName.ANTHROPIC).list();
ModelInfo raw = c.models().provider(ProviderName.ANTHROPIC).raw().get("claude-opus-4-7");
```

`live()` calls the configured provider's models endpoint and aggregates results into `LiveResult` — models plus a per-provider error map, so partial success is normal. `.raw()` opts into populating `ModelInfo.raw()` with the provider-native record.

### Capability query

```java
if (c.supports(Capability.CACHING)) {
    // the bound provider has a caching configuration
}
```

Synchronous, no IO. Says nothing about per-model rejections — use the catalogue's `ModelInfo.capabilities()` for model-level facts.

## Options

Across the `Text` builder:

| Concept          | Method                   |
| ---------------- | ------------------------ |
| System prompt    | `.system(s)`             |
| Model override   | `.model(name)`           |
| Sampling         | `.temperature(t)`        |
| Token cap        | `.maxTokens(n)`          |
| Caching          | `.caching()`             |
| Cache TTL        | `.cacheTtl(seconds)`     |
| Middleware hooks | `.addMiddleware(fn)`     |
| Reasoning effort | `.reasoningEffort(s)`    |
| Thinking budget  | `.thinkingBudget(n)`     |
| Structured output| `.schema(json)`          |
| Chat protocol    | `.protocol(name)`        |

Sampling hyperparameters (`.topP`, `.topK`, `.seed`, `.frequencyPenalty`, `.presencePenalty`, `.stopSequences`, `.safetySettings`) are validated per provider; unsupported options throw `ValidationException` rather than silently dropping. `Agent` adds `.addTool(t)` and `.maxToolIterations(n)` and carries conversation history implicitly across `.prompt(...)` calls.

## Self-hosted endpoints

```java
Client c = Client.openai("anything").baseUrl("http://localhost:8080/v1");
```

Works for any OpenAI-compatible server (vLLM, LM Studio, Ollama, corporate gateways). The same `.baseUrl(...)` seam substitutes account/project/region-in-URL providers (Workers AI, Vertex).

## Custom headers

Attach a custom HTTP header to every request — for example an authenticated gateway that needs its own auth header alongside the provider key. `addHeader` is chainable and calls accumulate.

```java
Client c = new Client(ProviderName.ANTHROPIC, apiKey)
        .baseUrl("https://gateway.example.com/anthropic")
        .addHeader("cf-aig-authorization", "Bearer " + gatewayToken);
```

The custom header is sent in addition to the provider's auth header; it cannot override the provider auth header or the required version header.

## Middleware

Register pre/post hooks around LLM requests, tool calls, image generation, cache creation, uploads, and batch submits. A non-null return in the pre phase vetoes the operation (surfaced as `MiddlewareVetoException`); post-phase return values are discarded.

```java
import com.aktagon.llmkit.Event;
import com.aktagon.llmkit.MiddlewareFn;
import com.aktagon.llmkit.MiddlewareOp;
import com.aktagon.llmkit.MiddlewarePhase;

// Observation: log token usage after every LLM request.
MiddlewareFn logUsage = event -> {
    if (event.op() == MiddlewareOp.LLM_REQUEST && event.phase() == MiddlewarePhase.POST) {
        System.out.println(event.provider() + "/" + event.model() + ": "
                + event.usage().input() + " in, " + event.usage().output() + " out, "
                + event.durationMillis() + " ms");
    }
    return null;
};

// Veto: block a request before it is sent.
MiddlewareFn budgetGate = event -> {
    if (event.op() == MiddlewareOp.LLM_REQUEST && event.phase() == MiddlewarePhase.PRE
            && budgetExceeded()) {
        return new IllegalStateException("daily budget exceeded");
    }
    return null;
};

Response resp = c.text()
        .addMiddleware(budgetGate)
        .addMiddleware(logUsage)
        .prompt("...");
```

Middlewares fire in registration order; the first non-null pre-phase return aborts.

## Telemetry

Opt-in OpenTelemetry. Attach a `Telemetry` and every call — success and rejection alike — produces one OTEL GenAI span (operation, provider, model, token usage, and `error.type` on failure) as standards-compliant OTLP/JSON bytes. llmkit builds the span; you decide where the bytes go. Off unless attached.

```java
import com.aktagon.llmkit.Telemetry;

// Batteries: POST every span to an OTLP collector.
Client client = c.addTelemetry(new Telemetry(
        Telemetry.httpExport("https://collector:4318", Map.of())));

// Or bring your own transport — hand the bytes to your OTEL SDK:
Client client2 = c.addTelemetry(new Telemetry(bytes -> processor.enqueue(bytes)));

Response resp = client.text().prompt("Hello");
```

`httpExport` is a synchronous, fail-open POST — convenient for low volume; for high volume hand your own consumer into your OTEL SDK's batch processor. The same OTLP span shape is emitted byte-for-byte across all six SDKs. Because the export consumer is a required constructor argument, an enabled-but-no-sink `Telemetry` cannot be constructed.

## Errors

Every failure is a subtype of `LlmKitException`: `ApiException` (provider-reported error envelope), `TransportException` (network failure — also wraps thread interruption, with the interrupt flag restored), `ValidationException` (rejected before any HTTP round-trip), `DecodingException` (malformed provider payload), `MiddlewareVetoException`, `JobFailedException`, `PollTimeoutException`, and `CatalogueException`. Catch the root type at boundaries or discriminate on the subtype.

## Mirror

This repo is a read-only mirror of a private monorepo. File issues here; code patches should target the private source via `christian@aktagon.com`.

## License

MIT
