package io.github.adeshsuryan.springtxdoctor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An evidence-backed diagnostic finding. Immutable once created via the
 * fluent constructor-style usage in this class; occurrence counts are
 * tracked by the sink, not here.
 */
public final class TxFinding {

    private final String ruleCode;
    private final Severity severity;
    private final Confidence confidence;
    private final String transactionName;   // nullable — unnamed programmatic tx
    private final String managerBean;       // nullable — audit findings may not have one
    private final String message;
    private final Map<String, String> evidence;
    private final List<String> nextChecks;
    private final Instant firstSeenAt;

    private TxFinding(Builder b) {
        this.ruleCode = b.ruleCode;
        this.severity = b.severity;
        this.confidence = b.confidence;
        this.transactionName = b.transactionName;
        this.managerBean = b.managerBean;
        this.message = b.message;
        this.evidence = Map.copyOf(b.evidence);
        this.nextChecks = List.copyOf(b.nextChecks);
        this.firstSeenAt = b.firstSeenAt;
    }

    public static Builder rule(String ruleCode, Severity severity, Confidence confidence) {
        return new Builder(ruleCode, severity, confidence);
    }

    public String ruleCode() { return ruleCode; }
    public Severity severity() { return severity; }
    public Confidence confidence() { return confidence; }
    public String transactionName() { return transactionName; }
    public String managerBean() { return managerBean; }
    public String message() { return message; }
    public Map<String, String> evidence() { return evidence; }
    public List<String> nextChecks() { return nextChecks; }
    public Instant firstSeenAt() { return firstSeenAt; }

    /** Deduplication key: rule + transaction + manager. */
    public String dedupKey() {
        return ruleCode + "|" + (transactionName == null ? "-" : transactionName)
                + "|" + (managerBean == null ? "-" : managerBean);
    }

    public static final class Builder {
        private final String ruleCode;
        private final Severity severity;
        private final Confidence confidence;
        private String transactionName;
        private String managerBean;
        private String message = "";
        private final Map<String, String> evidence = new LinkedHashMap<>();
        private final List<String> nextChecks = new ArrayList<>();
        private Instant firstSeenAt = Instant.now();

        private Builder(String ruleCode, Severity severity, Confidence confidence) {
            this.ruleCode = ruleCode;
            this.severity = severity;
            this.confidence = confidence;
        }

        public Builder transaction(String name) { this.transactionName = name; return this; }
        public Builder manager(String bean) { this.managerBean = bean; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder evidence(String key, Object value) {
            this.evidence.put(key, String.valueOf(value));
            return this;
        }
        public Builder nextCheck(String check) { this.nextChecks.add(check); return this; }
        public Builder at(Instant instant) { this.firstSeenAt = instant; return this; }
        public TxFinding build() { return new TxFinding(this); }
    }
}
