package io.github.adeshsuryan.springtxdoctor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import io.github.adeshsuryan.springtxdoctor.audit.TransactionAuditor;
import io.github.adeshsuryan.springtxdoctor.observe.TxInterceptorPostProcessor;
import io.github.adeshsuryan.springtxdoctor.report.JsonReportWriter;
import io.github.adeshsuryan.springtxdoctor.report.TextReportWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Boot autoconfiguration: startup audit (on by default, read-only)
 * and opt-in runtime observation (transaction-manager decoration).
 */
@AutoConfiguration(after = TransactionAutoConfiguration.class)
@ConditionalOnClass(PlatformTransactionManager.class)
@ConditionalOnProperty(prefix = "spring.transaction-doctor", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TxDoctorProperties.class)
public class TxDoctorAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TxDoctorAutoConfiguration.class);
    private static final String VERSION = "0.1.0";

    /**
     * Decorates TransactionInterceptor beans when observe mode is on.
     * Declared static so the post-processor registers before regular beans
     * are eligible for post-processing. Dependencies are ObjectProviders:
     * a static post-processor instantiates too early for
     * ConfigurationProperties binding, so resolution must be lazy.
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.transaction-doctor.observe",
            name = "enabled", havingValue = "true")
    public static BeanPostProcessor txInterceptorObservingPostProcessor(
            ObjectProvider<TxDoctorProperties> props,
            ObjectProvider<FindingSink> sink) {
        return new TxInterceptorPostProcessor(props, sink);
    }

    @Bean
    public FindingSink txDoctorFindingSink(TxDoctorProperties props) {
        return new FindingSink(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.transaction-doctor.audit",
            name = "enabled", matchIfMissing = true)
    public SmartInitializingSingleton txDoctorAuditRunner(
            ListableBeanFactory beanFactory, Environment environment,
            TxDoctorProperties props, FindingSink sink) {
        return () -> {
            try {
                List<TxFinding> findings =
                        new TransactionAuditor(environment).audit(beanFactory);
                for (TxFinding f : findings) {
                    sink.emit(f);
                }
                if (findings.isEmpty() && props.getAudit().isLogHealthy()) {
                    sink.emit(TxFinding
                            .rule("TX_AUDIT_HEALTHY", Severity.INFO, Confidence.MEASURED)
                            .message("Transaction audit completed — no findings")
                            .build());
                }
                writeReport(props, sink);
            } catch (Throwable t) {
                LOG.warn("spring-transaction-doctor audit failed safely: {}",
                        t.getClass().getSimpleName());
            }
        };
    }

    private static void writeReport(TxDoctorProperties props, FindingSink sink) {
        String path = props.getReport().getPath();
        if (path == null || path.isBlank()) {
            return;
        }
        String content = "json".equals(props.getReport().getFormat())
                ? JsonReportWriter.write(VERSION, sink.findings(),
                        sink.occurrenceCounts(), sink.events())
                : TextReportWriter.write(sink.findings(),
                        sink.occurrenceCounts(), sink.events());
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
