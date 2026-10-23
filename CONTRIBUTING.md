# Contributing

Thanks for your interest. A few ground rules keep this package honest:

## Before you code

- Search the issues; describe the false positive, missed detection, or
  overhead you are seeing.
- A reproducible fixture beats a description: a small Spring Boot app or
  JUnit test that demonstrates the behaviour.

## Rules of the codebase

- **Read-only guarantee** — the library never mutates transactions, beans,
  or application state. Instrumentation must fail open: any internal error
  must not change the real outcome.
- **No data capture** — no SQL, no bind values, no exception messages, no
  request/user identifiers. Exception class names only.
- **Honest confidence** — every finding carries `measured` | `suspected` |
  `inferred`. Never present a heuristic as a proven root cause.
- **Zero required runtime dependencies** — a new dependency needs a
  justification in the PR plus lines in `SECURITY.md` and `CHANGELOG.md`.
- **Rule IDs are frozen** once published. Changing detection logic is fine;
  changing or removing a published code is a breaking change.
- Every rule needs a positive fixture **and** a negative fixture.

## Build

```bash
mvn clean verify
```

Java 17+, Maven 3.9+. See `README.md` for the tested Spring Boot matrix.

## Commit authorship

Commits are authored as Adesh Suryan. AI-generated contributions are
welcome but carry no tool attribution in commit messages or code comments.
