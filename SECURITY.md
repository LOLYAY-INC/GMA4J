# Security Policy

## Reporting a Vulnerability

The GMA4J maintainers take the security of this library seriously. If you
believe you have discovered a security vulnerability, we ask that you disclose
it to us privately and responsibly.

Please do **not** report security issues through public GitHub issues, pull
requests, or discussions.

Instead, please send a detailed report by email to:

**security@lolyay.dev**

### Information to Include

To help us assess and resolve the issue as quickly as possible, please provide
as much of the following as you are able:

- A clear description of the vulnerability and its potential impact.
- The affected module(s) and version(s).
- Detailed steps to reproduce the issue, including any proof-of-concept code.
- Relevant logs, stack traces, or configuration details.
- Your assessment of the severity, where possible.

### Our Commitment

- We will acknowledge receipt of your report within a reasonable timeframe.
- We will investigate the report and keep you informed of our progress.
- We will work to remediate confirmed vulnerabilities promptly and will
  coordinate the timing of any public disclosure with you.
- We kindly ask that you allow us a reasonable period to address the issue
  before disclosing it publicly.

## Supported Versions

Security updates are provided for the most recent released version of GMA4J.
Users are strongly encouraged to keep their dependency current.

## Scope

This policy applies to the GMA4J library and its published modules
(`gma4j-shared`, `gma4j-client`, `gma4j-server`, `gma4j-ws`, and
`gma4j-netty`, and optional `gma4j-delivery`). Vulnerabilities in third-party dependencies should be reported
to their respective maintainers.

## Durable receipt boundaries

The optional delivery module ACKs evidence only after its inbox transaction commits. This proves receipt by the authenticated peer, not application processing, a third-party attestation, or exactly-once execution. Bind each delivery session to an authenticated identity and pin server certificates. No-auth connections and shared credentials that allow identity impersonation cannot establish distinct trusted senders.

The H2 evidence store contains plaintext payloads and identifiers. Restrict filesystem access, use encrypted storage where required, and protect backups. Immediate log flushing requests crash durability from the database and OS; it cannot compensate for lost disks or storage hardware that ignores flushes. Restoring or deleting an inbox can discard deduplication history. Keep sender and receiver recovery policies consistent.

Record and payload quotas reject new evidence at capacity without ACKing it. Processed receipt tombstones are retained rather than silently expired. The quotas bound logical data, not the exact H2 file size or all process memory. Transport queue limits likewise exclude kernel socket buffers. See [durable delivery](README.md#durable-evidence-delivery) for the supported contract.

Thank you for helping to keep GMA4J and its users secure.
