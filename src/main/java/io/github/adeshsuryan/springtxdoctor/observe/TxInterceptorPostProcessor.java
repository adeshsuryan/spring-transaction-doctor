package io.github.adeshsuryan.springtxdoctor.observe;

import io.github.adeshsuryan.springtxdoctor.Confidence;
import io.github.adeshsuryan.springtxdoctor.FindingSink;
import io.github.adeshsuryan.springtxdoctor.Severity;
import io.github.adeshsuryan.springtxdoctor.TxDoctorProperties;
import io.github.adeshsuryan.springtxdoctor.TxFinding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Wraps every {@link TransactionInterceptor} bean in a
 * {@link DoctorTransactionInterceptor} — a delegating subclass, so the
 * concrete type is preserved exactly (no proxy framework, no bytecode,
 * no reflection risk). The advisor chain picks up the wrapper because it
 * resolves the interceptor through the bean factory.
 *
 * <p>If a bean cannot be wrapped it is passed through unchanged and
 * {@code TX_OBSERVE_SKIPPED_MANAGER} is emitted — never a silent skip.
 */
public final class TxInterceptorPostProcessor implements BeanPostProcessor, Ordered {

    private static final Logger LOG =
            LoggerFactory.getLogger(TxInterceptorPostProcessor.class);

    private final ObjectProvider<TxDoctorProperties> propsProvider;
    private final ObjectProvider<FindingSink> sinkProvider;
    private volatile TxDoctorProperties props;
    private volatile FindingSink sink;

    public TxInterceptorPostProcessor(ObjectProvider<TxDoctorProperties> propsProvider,
                                      ObjectProvider<FindingSink> sinkProvider) {
        this.propsProvider = propsProvider;
        this.sinkProvider = sinkProvider;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        if (!(bean instanceof TransactionInterceptor ti)
                || bean instanceof DoctorTransactionInterceptor) {
            return bean;
        }
        init();
        try {
            return new DoctorTransactionInterceptor(ti, beanName, sink, props);
        } catch (Throwable t) {
            LOG.debug("spring-transaction-doctor: skipping interceptor '{}': {}",
                    beanName, t.toString());
            sink.emit(TxFinding
                    .rule("TX_OBSERVE_SKIPPED_MANAGER", Severity.INFO,
                            Confidence.MEASURED)
                    .manager(beanName)
                    .message("Transaction interceptor '" + beanName
                            + "' is not observed: " + t.getClass().getSimpleName())
                    .evidence("reason", t.getClass().getName())
                    .build());
            return bean;
        }
    }

    private void init() {
        if (props == null) {
            props = propsProvider.getIfAvailable(TxDoctorProperties::new);
        }
        if (sink == null) {
            TxDoctorProperties p = props;
            sink = sinkProvider.getIfAvailable(() -> new FindingSink(p));
        }
    }
}
