# Changelog

## 0.1.0 (unreleased)

Initial release.

- Startup audit of Spring declarative-transaction wiring: non-public and
  `final` `@Transactional` methods, unproxied annotated beans, ambiguous
  transaction-manager beans, checked-exception rollback gaps,
  interface-only annotation resolution.
- Opt-in runtime observation via type-preserving transaction-manager
  proxy: `REQUIRES_NEW` suspensions, rollback-only chains
  (`UnexpectedRollbackException`), `MANDATORY`/`NEVER` violations,
  `NESTED` without savepoint support, long transactions, rollback storms.
- Text and zero-dependency JSON report; sanitized, rate-limited output;
  per-rule enable switch.
- Spring Boot 3.x autoconfiguration with safe defaults (audit on, observe
  off); `spring.transaction-doctor.*` property namespace.
