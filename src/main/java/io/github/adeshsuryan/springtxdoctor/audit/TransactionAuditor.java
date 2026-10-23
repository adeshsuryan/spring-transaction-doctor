package io.github.adeshsuryan.springtxdoctor.audit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.adeshsuryan.springtxdoctor.Confidence;
import io.github.adeshsuryan.springtxdoctor.Severity;
import io.github.adeshsuryan.springtxdoctor.TxDoctorProperties;
import io.github.adeshsuryan.springtxdoctor.TxFinding;

import org.springframework.aop.support.AopUtils;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * One-pass, read-only audit of declarative-transaction wiring, run once at
 * context refresh. For every bean method that carries a
 * {@code @Transactional} annotation (Spring or JTA), compares annotation
 * presence against what the configured {@link TransactionAttributeSource}
 * and the effective proxy mode can actually intercept. Never initializes
 * beans that are not already singletons.
 */
public final class TransactionAuditor {

    static final String JTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    private static final Set<String> SKIP_PREFIXES = Set.of(
            "java.", "jdk.", "javax.", "jakarta.", "org.springframework.");

    private final Environment environment;

    public TransactionAuditor(Environment environment) {
        this.environment = environment;
    }

    public List<TxFinding> audit(ListableBeanFactory beanFactory) {
        List<TxFinding> findings = new ArrayList<>();
        boolean aspectj = isAspectjMode(beanFactory);
        boolean proxyTargetClass = environment == null
                || environment.getProperty("spring.aop.proxy-target-class", Boolean.class, true);

        String[] managerNames = beanFactory.getBeanNamesForType(
                PlatformTransactionManager.class, false, false);
        boolean hasPrimary = hasPrimaryManager(beanFactory, managerNames);

        TransactionAttributeSource tas = resolveAttributeSource(beanFactory);

        boolean anyUnqualifiedTx = false;

        for (String beanName : beanFactory.getBeanNamesForType(Object.class)) {
            if (beanName.startsWith("&")) {
                continue;
            }
            Class<?> type = safeType(beanFactory, beanName);
            if (type == null || shouldSkip(type)) {
                continue;
            }

            Object bean = existingSingleton(beanFactory, beanName);
            boolean instantiated = bean != null;
            boolean aopProxy = instantiated && AopUtils.isAopProxy(bean);
            boolean cglib = instantiated && AopUtils.isCglibProxy(bean);
            Class<?> targetClass = instantiated
                    ? AopProxyUtils.ultimateTargetClass(bean)
                    : type;
            if (targetClass == null) {
                continue;
            }

            boolean beanHasResolvableTx = false;

            boolean jdkProxy = instantiated
                    ? AopUtils.isJdkDynamicProxy(bean) : !proxyTargetClass;

            for (Method method : uniqueMethods(targetClass)) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                int mod = method.getModifiers();
                boolean annotated = isTxAnnotated(method, targetClass);
                if (!annotated) {
                    continue;
                }
                if (Modifier.isStatic(mod)) {
                    findings.add(TxFinding
                            .rule("TX_ON_STATIC_METHOD", Severity.WARNING, Confidence.MEASURED)
                            .transaction(targetClass.getName() + "." + method.getName())
                            .message("@Transactional on a static method is never applied — "
                                    + "proxies intercept instance methods only")
                            .evidence("bean", beanName)
                            .evidence("method", signature(method))
                            .nextCheck("Remove the annotation or move the call to an "
                                    + "instance method")
                            .build());
                    continue;
                }
                TransactionAttribute attr = safeAttribute(tas, method, targetClass);
                // Structural reachability: proxies can only dispatch to methods
                // they can override/expose. Detection does NOT rely on attribute
                // resolution — in Spring 6 the attribute source resolves
                // annotations on non-public methods too.
                if (!aspectj) {
                    if (Modifier.isPrivate(mod)) {
                        findings.add(nonPublicFinding(beanName, method, targetClass,
                                "private — a private method can never be "
                                        + "intercepted by any proxy"));
                        continue;
                    }
                    boolean nonPublic = !Modifier.isPublic(mod);
                    if (nonPublic && (jdkProxy || attr == null)) {
                        findings.add(nonPublicFinding(beanName, method, targetClass,
                                jdkProxy
                                        ? "non-public method unreachable under a "
                                                + "JDK dynamic proxy"
                                        : "non-public method not advised by the "
                                                + "configured attribute source"));
                        continue;
                    }
                }
                if (attr == null) {
                    if (annotatedOnlyOnInterface(method, targetClass)) {
                        findings.add(TxFinding
                                .rule("TX_INTERFACE_ANNOTATION_UNRESOLVED",
                                        Severity.WARNING, Confidence.SUSPECTED)
                                .transaction(targetClass.getName() + "." + method.getName())
                                .message("@Transactional exists only on the interface "
                                        + "method and was not resolved on the target class")
                                .evidence("bean", beanName)
                                .evidence("method", signature(method))
                                .evidence("proxyMode",
                                        proxyTargetClass ? "class" : "jdk")
                                .nextCheck("Move the annotation onto the concrete "
                                        + "class method")
                                .nextCheck("Confirm the resolved "
                                        + "TransactionAttributeSource searches "
                                        + "interfaces for your Spring version")
                                .build());
                    }
                    continue;
                }
                if (Modifier.isPublic(mod)) {
                    beanHasResolvableTx = true;
                }
                if (unqualified(attr)) {
                    anyUnqualifiedTx = true;
                }
                if (!aspectj && cglibCapable(cglib, instantiated, proxyTargetClass)
                        && Modifier.isFinal(mod)) {
                    findings.add(TxFinding
                            .rule("TX_ON_FINAL_MEMBER", Severity.CRITICAL, Confidence.MEASURED)
                            .transaction(targetClass.getName() + "." + method.getName())
                            .manager(firstName(managerNames))
                            .message("@Transactional on a final method cannot be "
                                    + "intercepted by a CGLIB proxy")
                            .evidence("bean", beanName)
                            .evidence("method", signature(method))
                            .evidence("instantiated", instantiated)
                            .nextCheck("Remove 'final' from the method, or switch to "
                                    + "interface (JDK) proxies or AspectJ mode")
                            .nextCheck("Kotlin classes and methods are final by default — "
                                    + "apply the kotlin-spring (allopen) plugin")
                            .build());
                }
                findings.addAll(checkedExceptionFindings(method, attr, beanName, targetClass));
            }

            if (!aspectj && instantiated && !aopProxy && beanHasResolvableTx) {
                findings.add(TxFinding
                        .rule("TX_ANNOTATION_NOT_PROXYED", Severity.CRITICAL, Confidence.MEASURED)
                        .manager(firstName(managerNames))
                        .message("Bean " + beanName + " (" + targetClass.getName() + ") carries "
                                + "@Transactional methods but is NOT an AOP proxy — "
                                + "annotations will never apply")
                        .evidence("bean", beanName)
                        .evidence("class", targetClass.getName())
                        .nextCheck("Check @EnableTransactionManagement / auto-proxy coverage "
                                + "and how this bean was registered")
                        .nextCheck("Verify no self-registered or late-bound bean bypassed "
                                + "auto-proxying")
                        .build());
            }
        }

        if (managerNames.length > 1 && anyUnqualifiedTx) {
            findings.add(TxFinding
                    .rule("TX_MANAGER_AMBIGUOUS",
                            hasPrimary ? Severity.WARNING : Severity.CRITICAL,
                            Confidence.MEASURED)
                    .message(managerNames.length + " PlatformTransactionManager beans exist "
                            + "and @Transactional attributes are unqualified"
                            + (hasPrimary ? " — resolved via @Primary"
                                    : " — unqualified resolution will fail"))
                    .evidence("managers", String.join(",", managerNames))
                    .evidence("primaryPresent", hasPrimary)
                    .nextCheck("Qualify @Transactional(\"managerName\") or set a @Primary manager")
                    .nextCheck("Verify each service uses the intended manager")
                    .build());
        }
        return findings;
    }

    // ------------------------------------------------------------------

    private static TxFinding nonPublicFinding(String beanName, Method method,
            Class<?> targetClass, String reason) {
        return TxFinding
                .rule("TX_ON_NON_PUBLIC_METHOD", Severity.CRITICAL, Confidence.MEASURED)
                .transaction(targetClass.getName() + "." + method.getName())
                .message("@Transactional on a non-public method is silently "
                        + "ignored — " + reason)
                .evidence("bean", beanName)
                .evidence("method", signature(method))
                .evidence("visibility", visibility(method))
                .nextCheck("Make the method public — private methods are never "
                        + "intercepted; protected/package-private only under "
                        + "CGLIB with a matching attribute source")
                .nextCheck("Verify the call path is actually transactional")
                .build();
    }

    private static Method[] uniqueMethods(Class<?> type) {
        Set<Method> out = new LinkedHashSet<>();
        ReflectionUtils.doWithLocalMethods(type, out::add);
        ReflectionUtils.doWithMethods(type, out::add, ReflectionUtils.USER_DECLARED_METHODS);
        return out.toArray(new Method[0]);
    }

    static boolean isTxAnnotated(Method method, Class<?> targetClass) {
        if (AnnotatedElementUtils.findMergedAnnotation(method,
                org.springframework.transaction.annotation.Transactional.class) != null) {
            return true;
        }
        if (MergedAnnotations.from(method).get(JTA_TRANSACTIONAL).isPresent()) {
            return true;
        }
        return AnnotatedElementUtils.findMergedAnnotation(targetClass,
                org.springframework.transaction.annotation.Transactional.class) != null;
    }

    private static boolean annotatedOnlyOnInterface(Method method, Class<?> targetClass) {
        boolean onTarget = AnnotatedElementUtils.findMergedAnnotation(method,
                org.springframework.transaction.annotation.Transactional.class) != null
                || MergedAnnotations.from(method).get(JTA_TRANSACTIONAL).isPresent();
        if (onTarget) {
            return false;
        }
        for (Class<?> iface : targetClass.getInterfaces()) {
            Method im = ReflectionUtils.findMethod(iface, method.getName(), method.getParameterTypes());
            if (im != null && (AnnotatedElementUtils.findMergedAnnotation(im,
                    org.springframework.transaction.annotation.Transactional.class) != null
                    || MergedAnnotations.from(im).get(JTA_TRANSACTIONAL).isPresent())) {
                return true;
            }
        }
        return false;
    }

    private static List<TxFinding> checkedExceptionFindings(Method method,
            TransactionAttribute attr, String beanName, Class<?> targetClass) {
        List<TxFinding> out = new ArrayList<>();
        for (Class<?> ex : method.getExceptionTypes()) {
            if (RuntimeException.class.isAssignableFrom(ex)
                    || Error.class.isAssignableFrom(ex)) {
                continue;
            }
            if (coveredByRollbackRules(attr, ex)) {
                continue;
            }
            out.add(TxFinding
                    .rule("TX_CHECKED_EXCEPTION_ROLLBACK_GAP", Severity.WARNING,
                            Confidence.SUSPECTED)
                    .transaction(targetClass.getName() + "." + method.getName())
                    .message("Method declares checked exception " + ex.getSimpleName()
                            + " but no matching rollbackFor rule — it will commit, "
                            + "not roll back")
                    .evidence("bean", beanName)
                    .evidence("method", signature(method))
                    .evidence("exception", ex.getName())
                    .nextCheck("Add rollbackFor = " + ex.getSimpleName() + ".class if "
                            + "rollback is intended")
                    .nextCheck("Confirm the exception cannot carry a partial-write state")
                    .build());
        }
        return out;
    }

    private static boolean coveredByRollbackRules(TransactionAttribute attr, Class<?> ex) {
        if (!(attr instanceof RuleBasedTransactionAttribute rba)) {
            return false; // default: RuntimeException/Error only
        }
        boolean covered = false;
        for (RollbackRuleAttribute rule : rba.getRollbackRules()) {
            if (rule instanceof NoRollbackRuleAttribute) {
                if (matches(rule.getExceptionName(), ex)) {
                    return false;
                }
                continue;
            }
            if (matches(rule.getExceptionName(), ex)) {
                covered = true;
            }
        }
        return covered;
    }

    private static boolean matches(String ruleName, Class<?> ex) {
        if (ruleName == null) {
            return false;
        }
        if (ruleName.equals(ex.getName()) || ruleName.equals(ex.getSimpleName())) {
            return true;
        }
        try {
            Class<?> ruleClass = ClassUtils.forName(ruleName,
                    TransactionAuditor.class.getClassLoader());
            return ruleClass.isAssignableFrom(ex);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean unqualified(TransactionAttribute attr) {
        String q = attr.getQualifier();
        return q == null || q.isEmpty();
    }

    private TransactionAttributeSource resolveAttributeSource(ListableBeanFactory bf) {
        for (String name : bf.getBeanNamesForType(
                BeanFactoryTransactionAttributeSourceAdvisor.class, true, false)) {
            try {
                var advisor = bf.getBean(name,
                        BeanFactoryTransactionAttributeSourceAdvisor.class);
                if (advisor.getAdvice() instanceof TransactionInterceptor ti
                        && ti.getTransactionAttributeSource() != null) {
                    return ti.getTransactionAttributeSource();
                }
            } catch (Throwable ignored) {
            }
        }
        return new AnnotationTransactionAttributeSource();
    }

    private static TransactionAttribute safeAttribute(TransactionAttributeSource tas,
            Method method, Class<?> targetClass) {
        try {
            return tas == null ? null : tas.getTransactionAttribute(method, targetClass);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isAspectjMode(ListableBeanFactory bf) {
        try {
            for (String name : bf.getBeanDefinitionNames()) {
                if (bf instanceof ConfigurableListableBeanFactory c) {
                    BeanDefinition bd = c.getBeanDefinition(name);
                    String cn = bd.getBeanClassName();
                    if (cn == null) {
                        continue;
                    }
                    try {
                        Class<?> cls = ClassUtils.forName(cn, null);
                        var merged = MergedAnnotations.from(cls)
                                .get(EnableTransactionManagement.class);
                        if (merged.isPresent()) {
                            Object mode = merged.getValue("mode").orElse(null);
                            if (mode != null && "ASPECTJ".equals(mode.toString())) {
                                return true;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasPrimaryManager(ListableBeanFactory bf, String[] names) {
        if (!(bf instanceof ConfigurableListableBeanFactory c)) {
            return false;
        }
        for (String name : names) {
            try {
                if (c.getBeanDefinition(name).isPrimary()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean cglibCapable(boolean cglib, boolean instantiated,
            boolean proxyTargetClass) {
        return instantiated ? cglib : proxyTargetClass;
    }

    private static Object existingSingleton(ListableBeanFactory bf, String name) {
        if (bf instanceof DefaultSingletonBeanRegistry r && r.containsSingleton(name)) {
            try {
                return r.getSingleton(name);
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Class<?> safeType(ListableBeanFactory bf, String name) {
        try {
            return bf.getType(name, false);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean shouldSkip(Class<?> type) {
        String pkg = type.getPackageName();
        for (String prefix : SKIP_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String firstName(String[] names) {
        return names.length == 0 ? null : names[0];
    }

    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(pts[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static String visibility(Method m) {
        int mod = m.getModifiers();
        if (Modifier.isPrivate(mod)) {
            return "private";
        }
        if (Modifier.isProtected(mod)) {
            return "protected";
        }
        if (Modifier.isPublic(mod)) {
            return "public";
        }
        return "package-private";
    }
}
