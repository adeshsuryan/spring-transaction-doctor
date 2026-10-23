package io.github.adeshsuryan.springtxdoctor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Spring Transaction Doctor under
 * {@code spring.transaction-doctor.*}. Defaults are safe: the startup
 * audit runs read-only; runtime observation and all outputs are opt-in.
 */
@ConfigurationProperties(prefix = "spring.transaction-doctor")
public class TxDoctorProperties {

    /** Master switch. */
    private boolean enabled = true;

    private final Audit audit = new Audit();
    private final Observe observe = new Observe();
    private final Report report = new Report();
    private final Log log = new Log();
    private final Map<String, RuleToggle> rules = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Audit getAudit() { return audit; }
    public Observe getObserve() { return observe; }
    public Report getReport() { return report; }
    public Log getLog() { return log; }
    public Map<String, RuleToggle> getRules() { return rules; }

    /** Whether a rule code is enabled (default true). Lookup is
     * case-insensitive because relaxed binding lowercases map keys. */
    public boolean isRuleEnabled(String ruleCode) {
        for (Map.Entry<String, RuleToggle> e : rules.entrySet()) {
            if (e.getKey().equalsIgnoreCase(ruleCode) && e.getValue() != null) {
                return e.getValue().isEnabled();
            }
        }
        return true;
    }

    public static class Audit {
        /** Run the read-only startup audit at context refresh. */
        private boolean enabled = true;
        /** Log an INFO finding when the audit finds nothing. */
        private boolean logHealthy = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isLogHealthy() { return logHealthy; }
        public void setLogHealthy(boolean logHealthy) { this.logHealthy = logHealthy; }
    }

    public static class Observe {
        /** Weave a boundary interceptor into transactional proxies to
         * observe the runtime lifecycle. Off by default. */
        private boolean enabled = false;
        /** Transactions longer than this emit TX_LONG_DURATION (suspected). */
        private long longDurationThresholdMs = 3000;
        /** Maximum recent transaction events retained for reports. */
        private int bufferSize = 512;
        /** Rollbacks above this count per minute emit TX_ROLLBACK_STORM. */
        private int rollbackStormThreshold = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getLongDurationThresholdMs() { return longDurationThresholdMs; }
        public void setLongDurationThresholdMs(long v) { this.longDurationThresholdMs = v; }
        public int getBufferSize() { return bufferSize; }
        public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }
        public int getRollbackStormThreshold() { return rollbackStormThreshold; }
        public void setRollbackStormThreshold(int v) { this.rollbackStormThreshold = v; }
    }

    public static class Report {
        /** Optional file path for the JSON report. Off when unset — the
         * report contains class and method names; treat as sensitive. */
        private String path;
        /** Report format: text or json. */
        private String format = "text";

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format == null ? "text" : format.toLowerCase(Locale.ROOT); }
    }

    public static class Log {
        /** Per-rule dedup/summary window for log output. */
        private long rateLimitWindowMs = 60_000;

        public long getRateLimitWindowMs() { return rateLimitWindowMs; }
        public void setRateLimitWindowMs(long v) { this.rateLimitWindowMs = v; }
    }

    public static class RuleToggle {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
