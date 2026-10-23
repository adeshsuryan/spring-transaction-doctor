package io.github.adeshsuryan.springtxdoctor.observe;

import java.util.ArrayDeque;
import java.util.Deque;

import io.github.adeshsuryan.springtxdoctor.Confidence;
import io.github.adeshsuryan.springtxdoctor.FindingSink;
import io.github.adeshsuryan.springtxdoctor.Sanitizer;
import io.github.adeshsuryan.springtxdoctor.Severity;
import io.github.adeshsuryan.springtxdoctor.TxDoctorProperties;
import io.github.adeshsuryan.springtxdoctor.TxFinding;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A {@link TransactionInterceptor} that delegates all transaction work to
 * the application's real interceptor and observes the boundary.
 *
 * Why intercept here and not on {@link PlatformTransactionManager}: the
 * manager's {@code getTransaction}/{@code commit}/{@code rollback} methods
 * are {@code final} on {@link AbstractPlatformTransactionManager}, so a
 * class proxy cannot intercept them — and an Objenesis-instantiated proxy
 * shell would break them outright (null fields). The interceptor's
 * {@link #invoke(MethodInvocation)} wraps the whole lifecycle — begin,
 * commit, rollback — and sees richer evidence: the resolved attribute,
 * the prior transaction on the thread (suspension evidence), and every
 * exception the boundary produces.
 *
 * Fail-open: any error inside the observation path is swallowed; the
 * delegated call decides the real outcome.
 */
public class DoctorTransactionInterceptor extends TransactionInterceptor {

    private static final long serialVersionUID = 1L;
    private static final long STORM_WINDOW_NANOS = 60_000_000_000L;

    private final transient TransactionInterceptor delegate;
    private final transient String beanName;
    private final transient FindingSink sink;
    private final transient TxDoctorProperties props;
    private final transient Deque<Long> rollbackTimesNanos = new ArrayDeque<>();

    public DoctorTransactionInterceptor(TransactionInterceptor delegate,
            String beanName, FindingSink sink, TxDoctorProperties props) {
        this.delegate = delegate;
        this.beanName = beanName;
        this.sink = sink;
        this.props = props;
        // Mirror the delegate's wiring so anything reading OUR state still
        // sees the real configuration.
        if (delegate.getTransactionAttributeSource() != null) {
            setTransactionAttributeSource(delegate.getTransactionAttributeSource());
        }
        if (delegate.getTransactionManager() != null) {
            setTransactionManager(delegate.getTransactionManager());
        }
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        if (sink == null) {
            return delegate.invoke(invocation);
        }
        TransactionAttributeSource tas;
        org.springframework.transaction.interceptor.TransactionAttribute attr = null;
        Class<?> targetClass = targetClass(invocation);
        String txName = org.springframework.util.ClassUtils.getQualifiedMethodName(
                invocation.getMethod(), targetClass);
        try {
            tas = delegate.getTransactionAttributeSource();
            if (tas != null) {
                attr = tas.getTransactionAttribute(invocation.getMethod(), targetClass);
            }
        } catch (Throwable ignored) {
        }

        boolean priorActive;
        String priorName = null;
        try {
            priorActive = TransactionSynchronizationManager.isActualTransactionActive();
            if (priorActive) {
                priorName = TransactionSynchronizationManager.getCurrentTransactionName();
            }
        } catch (Throwable t) {
            priorActive = false;
        }

        boolean preFlagged = false;
        if (attr != null) {
            preFlagged = observePreInvocation(attr, txName, priorActive, priorName);
        }

        long start = System.nanoTime();
        try {
            Object result = delegate.invoke(invocation);
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            afterReturn(attr, txName, durationMs);
            return result;
        } catch (Throwable t) {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            afterThrow(attr, txName, t, priorActive, priorName, durationMs, preFlagged);
            throw t;
        }
    }

    // ----------------------------------------------------------- observe

    /**
     * Deterministic pre-checks run before the delegated call. Returns true
     * when a violation was already reported so the catch path does not
     * double-flag the resulting exception.
     */
    private boolean observePreInvocation(
            org.springframework.transaction.interceptor.TransactionAttribute attr,
            String txName, boolean priorActive, String priorName) {
        boolean flagged = false;
        try {
            int propagation = attr.getPropagationBehavior();
            // Propagation contract violations are deterministic before the
            // call — no guessing from the thrown exception afterwards.
            if ((propagation == org.springframework.transaction.TransactionDefinition
                            .PROPAGATION_MANDATORY && !priorActive)
                    || (propagation == org.springframework.transaction.TransactionDefinition
                            .PROPAGATION_NEVER && priorActive)) {
                sink.emit(TxFinding
                        .rule("TX_PROPAGATION_VIOLATION", Severity.CRITICAL,
                                Confidence.MEASURED)
                        .transaction(clean(txName))
                        .manager(managerName())
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
            if (priorActive
                    && propagation == org.springframework.transaction.TransactionDefinition
                            .PROPAGATION_REQUIRES_NEW) {
                sink.emit(TxFinding
                        .rule("TX_REQUIRES_NEW_SUSPENSION", Severity.WARNING,
                                Confidence.MEASURED)
                        .transaction(clean(txName))
                        .manager(managerName())
                        .message("REQUIRES_NEW suspended active transaction '"
                                + clean(priorName) + "'")
                        .evidence("innerTransaction", clean(txName))
                        .evidence("suspendedTransaction", clean(priorName))
                        .nextCheck("Verify the suspension is intentional — a common "
                                + "cause of premature commits, deadlocks and audit "
                                + "confusion")
                        .build());
            }
            if (propagation == org.springframework.transaction.TransactionDefinition
                            .PROPAGATION_NESTED
                    && !nestedAllowed()) {
                sink.emit(TxFinding
                        .rule("TX_NESTED_WITHOUT_SAVEPOINT", Severity.WARNING,
                                Confidence.MEASURED)
                        .transaction(clean(txName))
                        .manager(managerName())
                        .message("PROPAGATION_NESTED requested but the transaction "
                                + "manager does not support nested transactions")
                        .evidence("propagation", "NESTED")
                        .evidence("manager", String.valueOf(
                                delegate.getTransactionManager() == null ? "unknown"
                                        : delegate.getTransactionManager()
                                                .getClass().getName()))
                        .nextCheck("Use PROPAGATION_REQUIRED, or enable nested "
                                + "transactions (savepoints) on the manager")
                        .build());
            }
        } catch (Throwable ignored) {
        }
        return flagged;
    }

    private void afterReturn(
            org.springframework.transaction.interceptor.TransactionAttribute attr,
            String txName, long durationMs) {
        try {
            String name = attr == null ? null : clean(txName);
            sink.recordEvent(FindingSink.summaryLine(
                    name == null ? "<unnamed>" : name, "COMPLETED", durationMs, null));
            long threshold = props.getObserve().getLongDurationThresholdMs();
            if (attr != null && durationMs > threshold) {
                sink.emit(TxFinding
                        .rule("TX_LONG_DURATION", Severity.WARNING, Confidence.SUSPECTED)
                        .transaction(name)
                        .manager(managerName())
                        .message("Transactional method '" + name + "' ran for "
                                + durationMs + "ms inside its transaction "
                                + "(threshold " + threshold + "ms)")
                        .evidence("durationMs", durationMs)
                        .evidence("thread", Thread.currentThread().getName())
                        .nextCheck("Look for remote calls or heavy work inside the "
                                + "transaction boundary")
                        .nextCheck("A long transaction holds a pool connection — "
                                + "cross-reference spring-db-pool-doctor findings")
                        .build());
            }
        } catch (Throwable ignored) {
        }
    }

    private void afterThrow(
            org.springframework.transaction.interceptor.TransactionAttribute attr,
            String txName, Throwable t, boolean priorActive, String priorName,
            long durationMs, boolean preFlagged) {
        try {
            String name = attr == null ? null : clean(txName);
            if (t instanceof org.springframework.transaction
                    .NestedTransactionNotSupportedException && !preFlagged) {
                sink.emit(TxFinding
                        .rule("TX_NESTED_WITHOUT_SAVEPOINT", Severity.WARNING,
                                Confidence.MEASURED)
                        .transaction(name)
                        .manager(managerName())
                        .message("PROPAGATION_NESTED rejected by the transaction "
                                + "manager: " + t.getClass().getSimpleName())
                        .evidence("propagation", "NESTED")
                        .evidence("exception", t.getClass().getName())
                        .nextCheck("Use PROPAGATION_REQUIRED, or enable nested "
                                + "transactions (savepoints) on the manager")
                        .build());
            } else if (t instanceof IllegalTransactionStateException) {
                if (preFlagged) {
                    // already reported deterministically in pre-invocation
                } else {
                    // Completion-path IllegalTransactionStateException — a
                    // boundary error such as commit/rollback on a completed or
                    // rollback-only transaction, NOT a propagation violation.
                    sink.emit(TxFinding
                            .rule("TX_ROLLBACK_ONLY_AT_COMMIT", Severity.CRITICAL,
                                    Confidence.SUSPECTED)
                            .transaction(name)
                            .manager(managerName())
                            .message("Transaction boundary failed: "
                                    + t.getClass().getSimpleName()
                                    + " — likely a rollback-only or already-"
                                    + "completed transaction")
                            .evidence("transaction", name == null ? "unknown" : name)
                            .evidence("exception", t.getClass().getName())
                            .nextCheck("Find which inner method marked the "
                                    + "transaction rollback-only (often a swallowed "
                                    + "exception or an inner @Transactional rollback)")
                            .nextCheck("Check propagation boundaries between the "
                                    + "inner and outer transactional methods")
                            .build());
                }
            } else if (t instanceof UnexpectedRollbackException) {
                sink.emit(TxFinding
                        .rule("TX_ROLLBACK_ONLY_AT_COMMIT", Severity.CRITICAL,
                                Confidence.MEASURED)
                        .transaction(name)
                        .manager(managerName())
                        .message("A transaction boundary hit a rollback-only "
                                + "transaction — " + t.getClass().getSimpleName()
                                + " propagates to the caller")
                        .evidence("transaction", name == null ? "unknown" : name)
                        .evidence("outerTransaction",
                                TransactionSynchronizationManager
                                        .isActualTransactionActive()
                                        ? clean(TransactionSynchronizationManager
                                                .getCurrentTransactionName())
                                        : "unknown")
                        .evidence("exception", t.getClass().getName())
                        .nextCheck("Find which inner method marked the transaction "
                                + "rollback-only (often a swallowed exception or an "
                                + "inner @Transactional rollback)")
                        .nextCheck("Check propagation boundaries between the inner "
                                + "and outer transactional methods")
                        .build());
            }
            boolean rolledBack = attr != null && attr.rollbackOn(t);
            sink.recordEvent(FindingSink.summaryLine(
                    name == null ? "<unnamed>" : name,
                    rolledBack ? "ROLLED_BACK" : "THREW",
                    durationMs, t.getClass().getSimpleName()));
            if (rolledBack) {
                recordRollbackForStorm();
            }
        } catch (Throwable ignored) {
        }
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
                        .manager(managerName())
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

    private boolean nestedAllowed() {
        try {
            org.springframework.transaction.TransactionManager tm =
                    delegate.getTransactionManager();
            return !(tm instanceof AbstractPlatformTransactionManager aptm)
                    || aptm.isNestedTransactionAllowed();
        } catch (Throwable t) {
            return true; // cannot determine — do not pre-flag; the call decides
        }
    }

    private String managerName() {
        return beanName;
    }

    private static Class<?> targetClass(MethodInvocation invocation) {
        return invocation.getThis() == null
                ? invocation.getMethod().getDeclaringClass()
                : org.springframework.aop.support.AopUtils.getTargetClass(
                        invocation.getThis());
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

    // Forwarding — the advisor may read configuration off the advice object.

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (delegate != null) {
            delegate.setBeanFactory(beanFactory);
        }
    }

    @Override
    public void afterPropertiesSet() {
        // delegate is already initialized; nothing to validate here
    }
}
