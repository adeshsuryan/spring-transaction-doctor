package io.github.adeshsuryan.springtxdoctor.observe;

import java.util.ArrayDeque;
import java.util.Deque;

import io.github.adeshsuryan.springtxdoctor.Confidence;
import io.github.adeshsuryan.springtxdoctor.FindingSink;
import io.github.adeshsuryan.springtxdoctor.Sanitizer;
import io.github.adeshsuryan.springtxdoctor.Severity;
import io.github.adeshsuryan.springtxdoctor.TxDoctorProperties;
import io.github.adeshsuryan.springtxdoctor.TxFinding;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;

/**
 * Observes the declarative-transaction boundary from a
 * {@code PointcutAdvisor} ordered just outside the transaction advisor
 * ({@code LOWEST_PRECEDENCE - 1}). At that position every
 * {@code @Transactional} invocation passes through with:
 *
 * <ul>
 *   <li>the resolved {@link TransactionAttribute};</li>
 *   <li>the pre-call TSM state — whether a transaction was already active
 *       and its name (suspension / propagation evidence, deterministic
 *       BEFORE the call);</li>
 *   <li>the full lifecycle outcome — every commit failure and
 *       rollback exception propagates back through {@code proceed()}.</li>
 * </ul>
 *
 * <p>Fail-open: observation errors are swallowed; the real outcome is
 * never altered. No SQL, bind values, entity content, request/user IDs,
 * or exception messages are captured — exception class names only.
 */
public final class DoctorBoundaryInterceptor implements MethodInterceptor {

    private static final long STORM_WINDOW_NANOS = 60_000_000_000L;

    private final TransactionAttributeSource tas;
    private final TransactionManagerResolver managerResolver;
    private final FindingSink sink;
    private final TxDoctorProperties props;
    private final Deque<Long> rollbackTimesNanos = new ArrayDeque<>();

    /** Minimal indirection so the interceptor stays unit-testable. */
    @FunctionalInterface
    public interface TransactionManagerResolver {
        PlatformTransactionManager resolve(TransactionAttribute attr);
    }

    public DoctorBoundaryInterceptor(TransactionAttributeSource tas,
            TransactionManagerResolver managerResolver,
            FindingSink sink, TxDoctorProperties props) {
        this.tas = tas;
        this.managerResolver = managerResolver;
        this.sink = sink;
        this.props = props;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        TransactionAttribute attr = null;
        Class<?> targetClass = targetClass(invocation);
        String txName = ClassUtils.getQualifiedMethodName(
                invocation.getMethod(), targetClass);
        try {
            if (tas != null) {
                attr = tas.getTransactionAttribute(
                        invocation.getMethod(), targetClass);
            }
        } catch (Throwable ignored) {
        }
        if (attr == null) {
            return invocation.proceed();   // not a transactional method
        }

        boolean priorActive;
        String priorName = null;
        try {
            priorActive = TransactionSynchronizationManager
                    .isActualTransactionActive();
            if (priorActive) {
                priorName = TransactionSynchronizationManager
                        .getCurrentTransactionName();
            }
        } catch (Throwable t) {
            priorActive = false;
        }

        boolean preFlagged = observePreInvocation(
                attr, txName, priorActive, priorName);

        long start = System.nanoTime();
        try {
            Object result = invocation.proceed();
            afterReturn(txName, (System.nanoTime() - start) / 1_000_000L);
            return result;
        } catch (Throwable t) {
            afterThrow(attr, txName, t, priorName,
                    (System.nanoTime() - start) / 1_000_000L, preFlagged);
            throw t;
        }
    }

    // ----------------------------------------------------------- observe

    /**
     * Deterministic pre-checks. Returns true when a violation was already
     * reported so the catch path does not double-flag the exception.
     */
    private boolean observePreInvocation(TransactionAttribute attr,
            String txName, boolean priorActive, String priorName) {
        boolean flagged = false;
        try {
            int propagation = attr.getPropagationBehavior();
            if ((propagation == org.springframework.transaction.TransactionDefinition
                            .PROPAGATION_MANDATORY && !priorActive)
                    || (propagation == org.springframework.transaction.TransactionDefinition
                            .PROPAGATION_NEVER && priorActive)) {
                sink.emit(TxFinding
                        .rule("TX_PROPAGATION_VIOLATION", Severity.CRITICAL,
                                Confidence.MEASURED)
                        .transaction(clean(txName))
                        .message("Propagation " + propagationName(propagation)
                                + " cannot be satisfied — call will be rejected")
                        .evidence("propagation", propagationName(propagation))
                        .evidence("priorTransaction",
                                priorActive ? clean(priorName) : "none")
                        .nextCheck("Fix call ordering so the required transaction "
                                + "context exists, or correct the propagation setting")
                        .build());
                flagged = true;
            }
            if (priorActive && propagation
                    == org.springframework.transaction.TransactionDefinition
                            .PROPAGATION_REQUIRES_NEW) {
                sink.emit(TxFinding
                        .rule("TX_REQUIRES_NEW_SUSPENSION", Severity.WARNING,
                                Confidence.MEASURED)
                        .transaction(clean(txName))
                        .message("REQUIRES_NEW suspended active transaction '"
                                + clean(priorName) + "'")
                        .evidence("innerTransaction", clean(txName))
                        .evidence("suspendedTransaction", clean(priorName))
                        .nextCheck("Verify the suspension is intentional — a "
                                + "common cause of premature commits, deadlocks "
                                + "and audit confusion")
                        .build());
            }
            if (propagation == org.springframework.transaction.TransactionDefinition
                            .PROPAGATION_NESTED
                    && !nestedAllowed(attr)) {
                sink.emit(TxFinding
                        .rule("TX_NESTED_WITHOUT_SAVEPOINT", Severity.WARNING,
                                Confidence.MEASURED)
                        .transaction(clean(txName))
                        .message("PROPAGATION_NESTED requested but the transaction "
                                + "manager does not support nested transactions")
                        .evidence("propagation", "NESTED")
                        .evidence("manager", managerClass(attr))
                        .nextCheck("Use PROPAGATION_REQUIRED, or enable nested "
                                + "transactions (savepoints) on the manager")
                        .build());
            }
        } catch (Throwable ignored) {
        }
        return flagged;
    }

    private void afterReturn(String txName, long durationMs) {
        try {
            sink.recordEvent(FindingSink.summaryLine(
                    clean(txName), "COMPLETED", durationMs, null));
            long threshold = props.getObserve().getLongDurationThresholdMs();
            if (durationMs > threshold) {
                sink.emit(TxFinding
                        .rule("TX_LONG_DURATION", Severity.WARNING,
                                Confidence.SUSPECTED)
                        .transaction(clean(txName))
                        .message("Transactional method '" + clean(txName)
                                + "' ran for " + durationMs + "ms inside its "
                                + "transaction boundary (threshold "
                                + threshold + "ms)")
                        .evidence("durationMs", durationMs)
                        .evidence("thread", Thread.currentThread().getName())
                        .nextCheck("Look for remote calls or heavy work inside "
                                + "the transaction boundary")
                        .nextCheck("A long transaction holds a pool connection — "
                                + "cross-reference spring-db-pool-doctor findings")
                        .build());
            }
        } catch (Throwable ignored) {
        }
    }

    private void afterThrow(TransactionAttribute attr, String txName,
            Throwable t, String priorName,
            long durationMs, boolean preFlagged) {
        try {
            String name = clean(txName);
            if (t instanceof org.springframework.transaction
                    .NestedTransactionNotSupportedException && !preFlagged) {
                sink.emit(TxFinding
                        .rule("TX_NESTED_WITHOUT_SAVEPOINT", Severity.WARNING,
                                Confidence.MEASURED)
                        .transaction(name)
                        .message("PROPAGATION_NESTED rejected by the transaction "
                                + "manager: " + t.getClass().getSimpleName())
                        .evidence("propagation", "NESTED")
                        .evidence("exception", t.getClass().getName())
                        .nextCheck("Use PROPAGATION_REQUIRED, or enable nested "
                                + "transactions (savepoints) on the manager")
                        .build());
            } else if (t instanceof IllegalTransactionStateException) {
                if (!preFlagged) {
                    // Completion-path ITSE — boundary error such as
                    // commit/rollback on a completed or rollback-only tx,
                    // NOT a propagation rejection.
                    emitRollbackOnly(name, t, priorName, Confidence.SUSPECTED);
                }
            } else if (t instanceof UnexpectedRollbackException) {
                emitRollbackOnly(name, t, priorName, Confidence.MEASURED);
            }
            boolean rolledBack = attr.rollbackOn(t);
            sink.recordEvent(FindingSink.summaryLine(
                    name, rolledBack ? "ROLLED_BACK" : "THREW",
                    durationMs, t.getClass().getSimpleName()));
            if (rolledBack) {
                recordRollbackForStorm();
            }
        } catch (Throwable ignored) {
        }
    }

    private void emitRollbackOnly(String name, Throwable t,
            String priorName, Confidence c) {
        sink.emit(TxFinding
                .rule("TX_ROLLBACK_ONLY_AT_COMMIT", Severity.CRITICAL, c)
                .transaction(name)
                .message("Transaction boundary failed: "
                        + t.getClass().getSimpleName()
                        + " — a rollback-only or already-completed transaction")
                .evidence("transaction", name == null ? "unknown" : name)
                .evidence("outerTransaction",
                        priorName == null ? "none" : clean(priorName))
                .evidence("exception", t.getClass().getName())
                .nextCheck("Find which inner method marked the transaction "
                        + "rollback-only (often a swallowed exception or an "
                        + "inner @Transactional rollback)")
                .nextCheck("Check propagation boundaries between the inner "
                        + "and outer transactional methods")
                .build());
    }

    private void recordRollbackForStorm() {
        long now = System.nanoTime();
        synchronized (rollbackTimesNanos) {
            rollbackTimesNanos.addLast(now);
            while (!rollbackTimesNanos.isEmpty()
                    && now - rollbackTimesNanos.peekFirst() > STORM_WINDOW_NANOS) {
                rollbackTimesNanos.pollFirst();
            }
            if (rollbackTimesNanos.size()
                    > props.getObserve().getRollbackStormThreshold()) {
                sink.emit(TxFinding
                        .rule("TX_ROLLBACK_STORM", Severity.WARNING,
                                Confidence.SUSPECTED)
                        .message(rollbackTimesNanos.size()
                                + " rollbacks observed within 60s")
                        .evidence("rollbackCount", rollbackTimesNanos.size())
                        .evidence("window", "60s")
                        .nextCheck("Inspect the dominant rolling-back method")
                        .nextCheck("Check for retry loops re-entering "
                                + "transactional paths")
                        .build());
            }
        }
    }

    // ---------------------------------------------------------- plumbing

    private boolean nestedAllowed(TransactionAttribute attr) {
        try {
            PlatformTransactionManager tm =
                    managerResolver == null ? null : managerResolver.resolve(attr);
            return !(tm instanceof AbstractPlatformTransactionManager aptm)
                    || aptm.isNestedTransactionAllowed();
        } catch (Throwable t) {
            return true;
        }
    }

    private String managerClass(TransactionAttribute attr) {
        try {
            PlatformTransactionManager tm =
                    managerResolver == null ? null : managerResolver.resolve(attr);
            return tm == null ? "unknown" : tm.getClass().getName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static Class<?> targetClass(MethodInvocation invocation) {
        return invocation.getThis() == null
                ? invocation.getMethod().getDeclaringClass()
                : AopUtils.getTargetClass(invocation.getThis());
    }

    private static String clean(String s) {
        return Sanitizer.clean(s);
    }

    private static String propagationName(int propagation) {
        return switch (propagation) {
            case org.springframework.transaction.TransactionDefinition
                    .PROPAGATION_REQUIRED -> "REQUIRED";
            case org.springframework.transaction.TransactionDefinition
                    .PROPAGATION_SUPPORTS -> "SUPPORTS";
            case org.springframework.transaction.TransactionDefinition
                    .PROPAGATION_MANDATORY -> "MANDATORY";
            case org.springframework.transaction.TransactionDefinition
                    .PROPAGATION_REQUIRES_NEW -> "REQUIRES_NEW";
            case org.springframework.transaction.TransactionDefinition
                    .PROPAGATION_NOT_SUPPORTED -> "NOT_SUPPORTED";
            case org.springframework.transaction.TransactionDefinition
                    .PROPAGATION_NEVER -> "NEVER";
            case org.springframework.transaction.TransactionDefinition
                    .PROPAGATION_NESTED -> "NESTED";
            default -> "UNKNOWN(" + propagation + ")";
        };
    }
}
