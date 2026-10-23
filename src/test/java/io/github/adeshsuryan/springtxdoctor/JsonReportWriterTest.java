package io.github.adeshsuryan.springtxdoctor;

import java.util.List;
import java.util.Map;

import io.github.adeshsuryan.springtxdoctor.report.JsonReportWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonReportWriterTest {

    @Test
    void producesWellFormedReport() {
        TxFinding f = TxFinding
                .rule("TX_REQUIRES_NEW_SUSPENSION", Severity.WARNING, Confidence.MEASURED)
                .transaction("com.acme.Order.place")
                .manager("txManager")
                .message("suspended \"outer\"")
                .evidence("inner", "a.b.c")
                .nextCheck("check the boundary")
                .build();
        String json = JsonReportWriter.write("0.1.0", List.of(f),
                Map.of(f.dedupKey(), 3L), List.of("evt1"));
        assertFalse(json.contains(",\n}"), "trailing comma before close");
        assertFalse(json.contains(",\n  ]"), "trailing comma before array close");
        assertTrue(json.contains("\"ruleCode\": \"TX_REQUIRES_NEW_SUSPENSION\""));
        assertTrue(json.contains("\\\"outer\\\""), "quotes escaped");
        assertTrue(json.contains("\"occurrences\": 3"));
        assertTrue(json.contains("\"schemaVersion\": 1"));
    }

    @Test
    void emptyFindings() {
        String json = JsonReportWriter.write("0.1.0", List.of(), Map.of(), List.of());
        assertTrue(json.contains("\"findings\": []"));
        assertFalse(json.contains(",\n}"));
    }
}
