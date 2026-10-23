# Security Policy

## Supported versions

| Version | Supported |
| ------- | --------- |
| 0.1.x   | yes       |

## Reporting a vulnerability

Please report privately via GitHub Security Advisories on this repository.
Do not open a public issue for security-sensitive reports. We aim to
acknowledge within 7 days.

## What this library captures — and what it never captures

Spring Transaction Doctor is a read-only diagnostic library. By design it:

- **Never captures** SQL text, bind values, entity content, exception
  messages, request IDs, user IDs, or any application data.
- Records transaction metadata only: propagation, isolation, timeout,
  read-only flag, duration, outcome, and the transaction/manager name.
- Makes **no network calls** and has no telemetry of any kind.

## Structural information in reports

Findings include Java class and method names and Spring bean names. This
reveals application structure and should be treated as sensitive:

- JSON report file output is **off by default** (`spring.transaction-doctor.report.path`).
- The Actuator endpoint (0.2+) is **off by default** and must be secured
  with your application's management-endpoint security before exposure.
- All externally-originated strings (e.g. programmatically-set transaction
  names) are sanitized before logging or reporting — control characters
  and newlines are stripped and length is capped.

## Dependencies

The artifact declares **zero required runtime dependencies**. Spring and
Spring Boot are `optional`/`provided` — your application supplies them.
Every added dependency is listed here and in `CHANGELOG.md`.

## Verifying releases

Release artifacts are signed with GPG (fingerprint ends `E34C5FF0`) and
published with a CycloneDX SBOM. Verify signatures before deploying.
