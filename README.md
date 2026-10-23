# Spring Transaction Doctor

Find silent transaction bugs in Spring applications — before they corrupt
data or confuse on-call.

```
WARNING  TX_ON_NON_PUBLIC_METHOD      @Transactional on a non-public method is
         silently ignored — private methods are never intercepted
WARNING  TX_REQUIRES_NEW_SUSPENSION   REQUIRES_NEW suspended active
         transaction 'OrderService.checkout'
CRITICAL TX_ROLLBACK_ONLY_AT_COMMIT   A transaction boundary hit a
         rollback-only transaction — UnexpectedRollbackException propagates
```

## What it does

Two modes, both built on read-only observation:

| Mode | Default | Cost | Finds |
| ---- | ------- | ---- | ----- |
| **Audit** | on | runs once at startup, zero steady-state | silent proxy gaps (non-public, `final`, unproxied beans), ambiguous tx managers, checked-exception rollback gaps, interface-only annotations |
| **Observe** | off | only when enabled | `REQUIRES_NEW` suspensions, `MANDATORY`/`NEVER` violations, `NESTED` without savepoint support, rollback-only → `UnexpectedRollbackException` chains, long transactions, rollback storms |

**Honest findings**: every finding carries a `confidence` label —
`measured` (directly observed), `suspected` (strong pattern, verify), or
`inferred` — plus concrete `evidence` and `nextChecks` instead of
overclaimed root causes.

## Requirements

Java 17+, Spring Boot 3.2–3.5 (Spring Framework 6.1–6.2).

Not supported in 0.1.x: Spring Boot 4.x / Framework 7 (tracked),
reactive/`ReactiveTransactionManager`, AspectJ LTW mode (audit detects it
and skips proxy rules), programmatic `TransactionTemplate` usage.

## Install

Maven:

```xml
<dependency>
  <groupId>io.github.adeshsuryan</groupId>
  <artifactId>spring-transaction-doctor-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'io.github.adeshsuryan:spring-transaction-doctor-spring-boot-starter:0.1.0'
```

Autoconfiguration activates on the classpath. Start your app — the audit
logs findings at startup.

## Configuration

```yaml
spring:
  transaction-doctor:
    enabled: true            # master switch
    audit:
      enabled: true          # one-shot startup audit, read-only
      log-healthy: false     # also log when nothing is wrong
    observe:
      enabled: false         # opt-in runtime observation
      long-duration-threshold-ms: 3000
      rollback-storm-threshold: 10   # rollbacks per 60s
      buffer-size: 512       # bounded event window
    report:
      path:                  # e.g. build/tx-doctor.json — OFF by default
      format: text           # text | json
    rules:
      TX_LONG_DURATION:      # per-rule kill switch
        enabled: false
```

## What it will never do

- Capture SQL, bind values, entity data, request/user IDs, or exception
  messages (exception **class names** only).
- Change transaction behavior — the observation path is fail-open: any
  internal error is swallowed and the real outcome is untouched.
- Make network calls or send telemetry anywhere.
- Claim runtime detection of *self-invocation* (impossible — a `this.`
  call never crosses the proxy). See the static-scan roadmap.

## The report is sensitive

Findings reveal class/method names and bean layout. The JSON report file
and (0.2+) Actuator endpoint are off by default — secure them before
exposing.

## Findings catalog

`TX_ON_NON_PUBLIC_METHOD` · `TX_ON_STATIC_METHOD` · `TX_ON_FINAL_MEMBER` ·
`TX_ANNOTATION_NOT_PROXYED` · `TX_INTERFACE_ANNOTATION_UNRESOLVED` ·
`TX_MANAGER_AMBIGUOUS` · `TX_CHECKED_EXCEPTION_ROLLBACK_GAP` ·
`TX_REQUIRES_NEW_SUSPENSION` · `TX_PROPAGATION_VIOLATION` ·
`TX_NESTED_WITHOUT_SAVEPOINT` · `TX_ROLLBACK_ONLY_AT_COMMIT` ·
`TX_LONG_DURATION` · `TX_ROLLBACK_STORM` · `TX_OBSERVE_SKIPPED_MANAGER` ·
`TX_AUDIT_HEALTHY`

## Ecosystem

| Tool | Question it answers |
| ---- | ------------------- |
| hibernate-doctor | What are my queries doing? |
| spring-db-pool-doctor | What is my connection pool doing? |
| **spring-transaction-doctor** | Are my transaction boundaries real and correct? |

## Development

```bash
mvn clean verify
```

24 tests cover every rule end-to-end with real Spring proxies. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the read-only/fail-open rules of
the codebase, and [SECURITY.md](SECURITY.md) for the threat model.
