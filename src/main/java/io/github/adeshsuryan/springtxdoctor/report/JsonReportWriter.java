package io.github.adeshsuryan.springtxdoctor.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.github.adeshsuryan.springtxdoctor.Sanitizer;
import io.github.adeshsuryan.springtxdoctor.TxFinding;

/**
 * Zero-dependency JSON report writer. Deterministic: findings sorted by
 * severity then first-seen time.
 */
public final class JsonReportWriter {

    private JsonReportWriter() {
    }

    public static String write(String version, List<TxFinding> findings,
                               Map<String, Long> occurrences, List<String> events) {
        List<TxFinding> sorted = findings.stream()
                .sorted(Comparator.comparing(TxFinding::severity)
                        .thenComparing(TxFinding::firstSeenAt))
                .toList();

        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"version\": \"").append(escape(version)).append("\",\n");
        sb.append("  \"schemaVersion\": 1,\n");
        sb.append("  \"findings\": [");
        if (sorted.isEmpty()) {
            sb.append("],\n");
        } else {
            sb.append('\n');
            for (int i = 0; i < sorted.size(); i++) {
                sb.append(findingJson(sorted.get(i), occurrences));
                if (i < sorted.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append("  ],\n");
        }
        sb.append("  \"recentTransactions\": [");
        List<String> items = new ArrayList<>();
        for (String e : events) {
            items.add("\"" + escape(e) + "\"");
        }
        sb.append(String.join(", ", items));
        sb.append("]\n}\n");
        return sb.toString();
    }

    private static String findingJson(TxFinding f, Map<String, Long> occurrences) {
        List<String> fields = new ArrayList<>();
        fields.add(kv("ruleCode", f.ruleCode()));
        fields.add(kv("severity", f.severity().name()));
        fields.add(kv("confidence", f.confidence().name()));
        if (f.transactionName() != null) {
            fields.add(kv("transactionName", f.transactionName()));
        }
        if (f.managerBean() != null) {
            fields.add(kv("managerBean", f.managerBean()));
        }
        fields.add(kv("message", Sanitizer.clean(f.message())));
        fields.add("      \"occurrences\": "
                + occurrences.getOrDefault(f.dedupKey(), 1L));
        fields.add(kv("firstSeenAt", f.firstSeenAt().toString()));

        StringBuilder ev = new StringBuilder("      \"evidence\": {");
        if (!f.evidence().isEmpty()) {
            List<String> pairs = new ArrayList<>();
            for (Map.Entry<String, String> e : f.evidence().entrySet()) {
                pairs.add("\"" + escape(e.getKey()) + "\": \""
                        + escape(Sanitizer.clean(e.getValue())) + "\"");
            }
            ev.append('\n').append("        ")
                    .append(String.join(",\n        ", pairs))
                    .append('\n').append("      ");
        }
        ev.append('}');
        fields.add(ev.toString());

        StringBuilder nc = new StringBuilder("      \"nextChecks\": [");
        List<String> checks = new ArrayList<>();
        for (String c : f.nextChecks()) {
            checks.add("\"" + escape(Sanitizer.clean(c)) + "\"");
        }
        nc.append(String.join(", ", checks)).append(']');
        fields.add(nc.toString());

        return "    {\n" + String.join(",\n", fields) + "\n    }";
    }

    private static String kv(String name, String value) {
        return "      \"" + name + "\": \"" + escape(value) + "\"";
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
