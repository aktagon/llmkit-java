# Security Policy

## Supported versions

Security fixes land in the latest published major release of this SDK on its
package registry. Older majors are not backported; upgrade to the current major
to receive fixes.

## Reporting a vulnerability

Report suspected vulnerabilities privately to **christian@aktagon.com**. Do not
open a public GitHub issue for a security problem — issues on this mirror are
visible to everyone before a fix ships.

Please include:

- the SDK and version you are using, and the runtime;
- a description of the issue and its impact;
- a minimal reproduction if you have one.

You can expect an acknowledgement within a few business days. Once a fix is
ready it ships as a patch release, and the advisory is noted in `CHANGELOG.md`.

## Scope

llmkit is a transport client: it forwards your request to a third-party LLM
provider over HTTP. API keys are read from your environment and sent only to the
configured provider endpoint. Vulnerabilities in a provider's own service should
be reported to that provider; report issues in this library's handling of keys,
requests, or responses here.
