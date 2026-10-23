package io.github.adeshsuryan.springtxdoctor.observe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Post-refresh weaver: finds every already-proxied bean whose advice
 * chain contains Spring's {@link TransactionInterceptor} and inserts the
 * observation advisor at position 0 (outermost — so pre-transaction TSM
 * state is visible at entry and every outcome propagates back).
 *
 * <p>Why this instead of a plain {@code Advisor} bean: advisor discovery
 * and ordering against Spring's internal transaction advisor proved
 * unreliable — the tx infrastructure beans and service proxies can be
 * created before a late autoconfiguration's advisor is eligible, leaving
 * the advice silently absent. Weaving after singleton instantiation is
 * deterministic and cannot be missed by ordering.
 *
 * <p>Limitation (documented): beans created lazily after refresh are not
 * woven — a restart-time log line reports how many proxies were woven.
 * Fail-open: any weaving error is logged at DEBUG and skipped.
 */
public final class TxBoundaryWeaver {

    private static final Logger LOG = LoggerFactory.getLogger(TxBoundaryWeaver.class);

    private TxBoundaryWeaver() {
    }

    /**
     * Weave the boundary interceptor into all existing transactional
     * proxies. Idempotent: a chain already carrying our advice is skipped.
     * Returns the number of proxies woven.
     */
    public static int weave(ListableBeanFactory beanFactory,
            TransactionAttributeSource tas,
            DoctorBoundaryInterceptor.TransactionManagerResolver resolver,
            io.github.adeshsuryan.springtxdoctor.FindingSink sink,
            io.github.adeshsuryan.springtxdoctor.TxDoctorProperties props) {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
                new TxAttributePointcut(tas),
                new DoctorBoundaryInterceptor(tas, resolver, sink, props));
        int woven = 0;
        // Instantiated singletons only — never force-initialize lazy beans.
        String[] names = beanFactory instanceof org.springframework.beans.factory
                        .config.ConfigurableBeanFactory cbf
                ? cbf.getSingletonNames()
                : beanFactory.getBeanDefinitionNames();
        for (String name : names) {
            Object bean;
            try {
                if (name.startsWith("&")) {
                    continue;
                }
                bean = beanFactory.getBean(name);
            } catch (BeansException e) {
                continue;
            }
            if (!(bean instanceof Advised advised) || containsOurAdvice(advised)) {
                continue;
            }
            if (!containsTxInterceptor(advised)) {
                continue;
            }
            try {
                advised.addAdvisor(0, advisor);
                woven++;
            } catch (Throwable t) {
                LOG.debug("spring-transaction-doctor: could not weave '{}': {}",
                        name, t.toString());
            }
        }
        if (woven > 0) {
            LOG.info("spring-transaction-doctor: observe mode active — "
                    + "watching {} transactional bean(s)", woven);
        }
        return woven;
    }

    private static boolean containsTxInterceptor(Advised advised) {
        for (Advisor a : advised.getAdvisors()) {
            if (a.getAdvice() instanceof TransactionInterceptor) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOurAdvice(Advised advised) {
        for (Advisor a : advised.getAdvisors()) {
            if (a.getAdvice() instanceof DoctorBoundaryInterceptor) {
                return true;
            }
        }
        return false;
    }

    /** Pointcut matching the methods the tx advisor would advise. */
    private static final class TxAttributePointcut
            extends StaticMethodMatcherPointcut {

        private final TransactionAttributeSource tas;

        TxAttributePointcut(TransactionAttributeSource tas) {
            this.tas = tas;
        }

        @Override
        public boolean matches(java.lang.reflect.Method m, Class<?> tc) {
            return tas.getTransactionAttribute(m, tc) != null;
        }
    }
}
