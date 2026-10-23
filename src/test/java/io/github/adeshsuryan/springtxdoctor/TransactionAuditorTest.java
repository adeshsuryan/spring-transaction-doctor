package io.github.adeshsuryan.springtxdoctor;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import io.github.adeshsuryan.springtxdoctor.audit.TransactionAuditor;
import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionAuditorTest {

    // ------------------------------------------------------------ fixtures

    static class PrivateTxService {
        @Transactional
        private void hidden() {
        }
    }

    static class StaticTxService {
        @Transactional
        static void nowhere() {
        }
    }

    static class CheckedExceptionService {
        @Transactional
        public void pay() throws IOException {
        }
    }

    static class RolledBackCheckedService {
        @Transactional(rollbackFor = IOException.class)
        public void pay() throws IOException {
        }
    }

    static class FinalMethodService {
        @Transactional
        public final void frozen() {
        }
    }

    static class PlainService {
        @Transactional
        public void work() {
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
        @Bean
        StubTxManager transactionManager() {
            return new StubTxManager();
        }
    }

    static AnnotationConfigApplicationContext context(Class<?>... classes) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.setEnvironment(new StandardEnvironment());
        ctx.register(classes);
        ctx.refresh();
        return ctx;
    }

    private List<TxFinding> audit(Class<?>... classes) {
        try (var ctx = context(classes)) {
            return new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
        }
    }

    private static List<String> codes(List<TxFinding> findings) {
        return findings.stream().map(TxFinding::ruleCode).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- tests

    @Test
    void privateMethodIsFlagged() {
        try (var ctx = context(TxConfig.class)) {
            ctx.registerBean("svc", PrivateTxService.class);
            List<TxFinding> findings =
                    new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
            assertTrue(codes(findings).contains("TX_ON_NON_PUBLIC_METHOD"));
        }
    }

    @Test
    void staticMethodIsFlagged() {
        try (var ctx = context(TxConfig.class)) {
            ctx.registerBean("svc", StaticTxService.class);
            List<TxFinding> findings =
                    new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
            assertTrue(codes(findings).contains("TX_ON_STATIC_METHOD"));
        }
    }

    @Test
    void checkedExceptionWithoutRollbackForIsFlagged() {
        try (var ctx = context(TxConfig.class)) {
            ctx.registerBean("svc", CheckedExceptionService.class);
            List<TxFinding> findings =
                    new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
            assertTrue(codes(findings).contains("TX_CHECKED_EXCEPTION_ROLLBACK_GAP"));
        }
    }

    @Test
    void rollbackForCoversCheckedException() {
        try (var ctx = context(TxConfig.class)) {
            ctx.registerBean("svc", RolledBackCheckedService.class);
            List<TxFinding> findings =
                    new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
            assertTrue(codes(findings).stream()
                    .noneMatch(c -> c.equals("TX_CHECKED_EXCEPTION_ROLLBACK_GAP")));
        }
    }

    @Test
    void finalMethodIsFlaggedUnderCglib() {
        try (var ctx = context(TxConfig.class)) {
            ctx.registerBean("svc", FinalMethodService.class);
            List<TxFinding> findings =
                    new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
            assertTrue(codes(findings).contains("TX_ON_FINAL_MEMBER"));
        }
    }

    @Test
    void unproxiedAnnotatedBeanIsFlagged() {
        // No @EnableTransactionManagement → bean carries annotation but no proxy
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.setEnvironment(new StandardEnvironment());
            ctx.registerBean("svc", PlainService.class);
            ctx.refresh();
            List<TxFinding> findings =
                    new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
            assertTrue(codes(findings).contains("TX_ANNOTATION_NOT_PROXYED"));
        }
    }

    @Test
    void proxiedHealthyServiceIsSilent() {
        try (var ctx = context(TxConfig.class)) {
            ctx.registerBean("svc", PlainService.class);
            List<TxFinding> findings =
                    new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
            assertEquals(List.of(), codes(findings),
                    "healthy proxied bean must produce no findings");
        }
    }

    @Test
    void twoManagersNoPrimaryIsCritical() {
        try (var ctx = context(TxConfig.class)) {
            ctx.registerBean("otherTxManager", StubTxManager.class);
            ctx.registerBean("svc", PlainService.class);
            List<TxFinding> findings =
                    new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
            TxFinding amb = findings.stream()
                    .filter(f -> f.ruleCode().equals("TX_MANAGER_AMBIGUOUS"))
                    .findFirst().orElseThrow();
            assertEquals(Severity.CRITICAL, amb.severity());
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class PrimaryCfg {
        @Bean
        @Primary
        StubTxManager primaryTx() {
            return new StubTxManager();
        }

        @Bean
        StubTxManager otherTx() {
            return new StubTxManager();
        }
    }

    @Test
    void twoManagersWithPrimaryIsWarning() {
        try (var ctx = context(PrimaryCfg.class)) {
            ctx.registerBean("svc", PlainService.class);
            List<TxFinding> findings =
                    new TransactionAuditor(ctx.getEnvironment()).audit(ctx.getBeanFactory());
            TxFinding amb = findings.stream()
                    .filter(f -> f.ruleCode().equals("TX_MANAGER_AMBIGUOUS"))
                    .findFirst().orElseThrow();
            assertEquals(Severity.WARNING, amb.severity());
        }
    }
}
