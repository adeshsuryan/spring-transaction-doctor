package io.github.adeshsuryan.springtxdoctor;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects findings, deduplicates them per {@link TxFinding#dedupKey()},
 * rate-limits SLF4J output (first occurrence immediately, then a summary
 * every {@code rateLimitWindowMs}), and retains a bounded window of
 * findings and transaction events for reports.
 */
public final class FindingSink {

    private static final Logger LOG = LoggerFactory.getLogger("io.github.adeshsuryan.springtxdoctor");

    private final TxDoctorProperties props;
    private final int capacity;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Deque<TxFinding> recentFindings;
    private final Deque<String> recentEvents;

    private static final class Entry {
        TxFinding finding;
        long occurrences;
        long firstLogged;
        long lastLogged;
    }

    public FindingSink(TxDoctorProperties props) {
        this.props = props;
        this.capacity = Math.max(64, props.getObserve().getBufferSize());
        this.recentFindings = new ArrayDeque<>(capacity);
        this.recentEvents = new ArrayDeque<>(capacity);
    }

    /** Record a finding; logs at WARN (or INFO for INFO severity) when due. */
    public synchronized void emit(TxFinding finding) {
        if (finding == null || !props.isRuleEnabled(finding.ruleCode())) {
            return;
        }
        // Bound the dedup map: a flood of distinct keys in observe mode
        // must not grow memory without limit.
        if (!entries.containsKey(finding.dedupKey())
                && entries.size() >= capacity * 4) {
            entries.remove(entries.keySet().iterator().next());
        }
        Entry e = entries.computeIfAbsent(finding.dedupKey(), k -> {
            Entry en = new Entry();
            en.finding = finding;
            return en;
        });
        e.occurrences++;
        long now = System.currentTimeMillis();
        long window = Math.max(1000, props.getLog().getRateLimitWindowMs());
        if (e.occurrences == 1) {
            log(finding, null);
            e.firstLogged = now;
            e.lastLogged = now;
            push(recentFindings, finding);
        } else if (now - e.lastLogged >= window) {
            log(e.finding, e.occurrences);
            e.lastLogged = now;
        }
    }

    private void log(TxFinding f, Long occurrences) {
        String suffix = occurrences == null ? "" : " (x" + occurrences + " since " + f.firstSeenAt() + ")";
        String line = "[" + f.ruleCode() + " " + f.severity() + "/" + f.confidence() + "] "
                + Sanitizer.clean(f.message())
                + (f.transactionName() == null ? "" : " tx=" + Sanitizer.clean(f.transactionName()))
                + (f.managerBean() == null ? "" : " manager=" + Sanitizer.clean(f.managerBean()))
                + suffix;
        if (f.severity() == Severity.INFO) {
            LOG.info(line);
        } else {
            LOG.warn(line);
        }
        for (String check : f.nextChecks()) {
            LOG.warn("  next-check: {}", Sanitizer.clean(check));
        }
    }

    /** Record a transaction lifecycle summary for the report. */
    public synchronized void recordEvent(String eventLine) {
        push(recentEvents, Sanitizer.clean(eventLine));
    }

    public synchronized List<TxFinding> findings() {
        List<TxFinding> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            out.add(e.finding);
        }
        return out;
    }

    public synchronized Map<String, Long> occurrenceCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            out.put(e.getKey(), e.getValue().occurrences);
        }
        return out;
    }

    public synchronized List<String> events() {
        return List.copyOf(recentEvents);
    }

    private <T> void push(Deque<T> deque, T item) {
        while (deque.size() >= capacity) {
            deque.pollFirst();
        }
        deque.addLast(item);
    }

    public static String summaryLine(String name, String outcome, long durationMs, String extra) {
        return Instant.now() + " " + name + " " + outcome + " " + durationMs + "ms"
                + (extra == null || extra.isEmpty() ? "" : " " + extra);
    }
}
