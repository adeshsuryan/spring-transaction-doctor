package io.github.adeshsuryan.springtxdoctor.report;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.github.adeshsuryan.springtxdoctor.Sanitizer;
import io.github.adeshsuryan.springtxdoctor.TxFinding;

/**
 * Human-readable console/CI report. Deterministic: findings sorted by
 * severity then first-seen time.
 */
public final class TextReportWriter {

    private TextReportWriter() {
    }

    public static String write(List<TxFinding> findings,
                               Map<String, Long> occurrences, List<String> events) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Spring Transaction Doctor\n\n");

        List<TxFinding> sorted = findings.stream()
                .sorted(Comparator.comparing(TxFinding::severity)
                        .thenComparing(TxFinding::firstSeenAt))
                .toList();
        sb.append("FINDINGS\n");
        if (sorted.isEmpty()) {
            sb.append("  none\n");
        }
        for (TxFinding f : sorted) {
            long n = occurrences.getOrDefault(f.dedupKey(), 1L);
            sb.append(String.format("%-8s %-27s %s%n",
                    f.severity(), f.ruleCode(), Sanitizer.clean(f.message())));
            if (f.transactionName() != null) {
                sb.append("         tx: ").append(Sanitizer.clean(f.transactionName())).append('\n');
            }
            sb.append("         confidence: ").append(f.confidence());
            if (n > 1) {
                sb.append("   occurrences: ").append(n);
            }
            sb.append('\n');
            for (String check : f.nextChecks()) {
                sb.append("         -> ").append(Sanitizer.clean(check)).append('\n');
            }
        }

        if (!events.isEmpty()) {
            sb.append("\nOBSERVED TRANSACTIONS\n");
            for (String e : events) {
                sb.append("  ").append(e).append('\n');
            }
        }
        return sb.toString();
    }
}
