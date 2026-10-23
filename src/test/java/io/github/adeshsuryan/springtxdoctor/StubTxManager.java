package io.github.adeshsuryan.springtxdoctor;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Minimal in-memory PlatformTransactionManager for tests — no DataSource,
 * no database. Binds a plain transaction object to the thread so join,
 * suspend/resume and nested checks behave like a real manager.
 */
public class StubTxManager extends AbstractPlatformTransactionManager {

    private final ThreadLocal<Object> current = new ThreadLocal<>();

    public int begins;
    public int commits;
    public int rollbacks;

    public StubTxManager() {
        setNestedTransactionAllowed(false);
    }

    @Override
    protected Object doGetTransaction() {
        Object tx = current.get();
        return tx != null ? tx : new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return current.get() != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        begins++;
        current.set(transaction);
    }

    @Override
    protected Object doSuspend(Object transaction) {
        Object suspended = current.get();
        current.remove();
        return suspended;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        if (suspendedResources != null) {
            current.set(suspendedResources);
        }
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rollbacks++;
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        current.remove();
    }
}
