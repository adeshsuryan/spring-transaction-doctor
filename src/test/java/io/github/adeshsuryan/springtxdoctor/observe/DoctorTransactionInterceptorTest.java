package io.github.adeshsuryan.springtxdoctor.observe;

import java.util.List;
import java.util.stream.Collectors;

import io.github.adeshsuryan.springtxdoctor.FindingSink;
import io.github.adeshsuryan.springtxdoctor.StubTxManager;
import io.github.adeshsuryan.springtxdoctor.TxDoctorProperties;
import io.github.adeshsuryan.springtxdoctor.TxFinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: services are proxied with Spring AOP, the wrapped
 * TransactionInterceptor is the advice, and the stub manager executes the
 * real transaction lifecycle. What a real app would experience.
 */
class DoctorTransactionInterceptorTest {

    private TxDoctorProperties props;
    private FindingSink sink;
    private StubTxManager manager;
    private TxInterceptorPostProcessor bpp;

    // ------------------------------------------------------- fixture svc

    public static class Outer {
        private final Inner inner;
        public Outer(Inner inner) { this.inner = inner; }

        @Transactional
        public void job() {
            inner.audit();
        }
    }

    public static class Inner {
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void audit() {
        }
    }

    public static class Joiner {
        @Transactional
        public void markRollbackOnly() {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    public static class Caller {
        private final Joiner joiner;
        public Caller(Joiner joiner) { this.joiner = joiner; }

        @Transactional
        public void outer() {
            joiner.markRollbackOnly(); // joins, marks rollback-only
        }
    }

    public static class MandatoryService {
        @Transactional(propagation = Propagation.MANDATORY)
        public void must() {
        }
    }

    public static class NestedService {
        @Transactional(propagation = Propagation.NESTED)
        public void nest() {
        }
    }

    /** NESTED only diverges from REQUIRED inside an existing transaction. */
    public static class NestedCaller {
        private final NestedService nested;
        public NestedCaller(NestedService nested) { this.nested = nested; }

        @Transactional
        public void outer() {
            nested.nest();
        }
    }

    public static class SlowService {
        @Transactional
        public void slow() throws InterruptedException {
            Thread.sleep(5);
        }
    }

    public static class FlakyService {
        @Transactional
        public void boom() {
            throw new IllegalStateException("forced");
        }
    }

    public static class HealthyService {
        @Transactional
        public void ok() {
        }
    }

    // ------------------------------------------------------------- setup

    @BeforeEach
    void setUp() {
        props = new TxDoctorProperties();
        props.getObserve().setEnabled(true);
        sink = new FindingSink(props);
        manager = new StubTxManager();
        bpp = new TxInterceptorPostProcessor(provider(props), provider(sink));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    private <T> ObjectProvider<T> provider(T obj) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return obj; }
        };
    }

    /** Build the observed advice chain and return it. */
    private TransactionInterceptor doctorInterceptor() {
        TransactionInterceptor real = new TransactionInterceptor();
        real.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        real.setTransactionManager(manager);
        real.afterPropertiesSet();
        return (TransactionInterceptor)
                bpp.postProcessAfterInitialization(real, "transactionInterceptor");
    }

    /** Proxy a service whose @Transactional methods route through the
     * doctor-wrapped interceptor — exactly like @EnableTransactionManagement. */
    @SuppressWarnings("unchecked")
    private <T> T proxied(T target) {
        ProxyFactory pf = new ProxyFactory(target);
        pf.setProxyTargetClass(true);
        pf.addAdvice(doctorInterceptor());
        return (T) pf.getProxy();
    }

    private List<String> codes() {
        return sink.findings().stream().map(TxFinding::ruleCode)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------- tests

    @Test
    void wrapPreservesTypeAndSecondWrapSkipped() {
        TransactionInterceptor wrapped = doctorInterceptor();
        assertTrue(wrapped instanceof DoctorTransactionInterceptor);
        assertTrue(wrapped instanceof TransactionInterceptor);
        Object twice = bpp.postProcessAfterInitialization(wrapped, "transactionInterceptor");
        assertTrue(twice == wrapped, "must not double-wrap");
    }

    @Test
    void nonInterceptorBeanPassesThrough() {
        Object bean = new Object();
        assertTrue(bpp.postProcessAfterInitialization(bean, "x") == bean);
    }

    @Test
    void requiresNewSuspensionIsFlagged() {
        Outer outer = proxied(new Outer(proxied(new Inner())));
        outer.job();
        assertTrue(codes().contains("TX_REQUIRES_NEW_SUSPENSION"));
        assertTrue(sink.events().stream().anyMatch(e -> e.contains("Inner.audit")));
    }

    @Test
    void rollbackOnlyChainIsFlagged() {
        Caller caller = proxied(new Caller(proxied(new Joiner())));
        // Inner boundary hits UnexpectedRollbackException; the outer boundary's
        // attempt to roll back the completed tx may surface as
        // IllegalTransactionStateException — same root cause family.
        assertThrows(org.springframework.transaction.TransactionException.class,
                caller::outer);
        assertTrue(codes().contains("TX_ROLLBACK_ONLY_AT_COMMIT"));
    }

    @Test
    void mandatoryViolationIsFlaggedAndRethrown() {
        MandatoryService svc = proxied(new MandatoryService());
        assertThrows(IllegalTransactionStateException.class, svc::must);
        assertTrue(codes().contains("TX_PROPAGATION_VIOLATION"));
    }

    @Test
    void nestedWithoutSavepointIsFlagged() {
        NestedCaller caller = proxied(new NestedCaller(proxied(new NestedService())));
        assertThrows(NestedTransactionNotSupportedException.class, caller::outer);
        assertTrue(codes().contains("TX_NESTED_WITHOUT_SAVEPOINT"));
    }

    @Test
    void longDurationIsFlagged() {
        props.getObserve().setLongDurationThresholdMs(0);
        SlowService svc = proxied(new SlowService());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(svc::slow);
        assertTrue(codes().contains("TX_LONG_DURATION"));
    }

    @Test
    void rollbackStormIsFlagged() {
        props.getObserve().setRollbackStormThreshold(3);
        FlakyService svc = proxied(new FlakyService());
        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalStateException.class, svc::boom);
        }
        assertTrue(codes().contains("TX_ROLLBACK_STORM"));
    }

    @Test
    void healthyTransactionIsSilent() {
        HealthyService svc = proxied(new HealthyService());
        svc.ok();
        assertEquals(List.of(), codes());
        assertEquals(1, manager.commits);
        assertTrue(sink.events().get(0).contains("COMPLETED"));
    }

    @Test
    void observedOutcomeDoesNotAlterBehaviour() {
        // the real call must return the real result through the wrapper
        HealthyService svc = proxied(new HealthyService());
        svc.ok();
        assertEquals(1, manager.begins);
        assertEquals(1, manager.commits);
        assertEquals(0, manager.rollbacks);
    }
}
