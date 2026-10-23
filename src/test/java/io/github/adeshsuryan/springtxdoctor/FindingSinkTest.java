package io.github.adeshsuryan.springtxdoctor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingSinkTest {

    private static TxFinding finding(String code, String tx) {
        return TxFinding.rule(code, Severity.WARNING, Confidence.MEASURED)
                .transaction(tx).message("m").build();
    }

    @Test
    void dedupsByKeyAndCountsOccurrences() {
        var sink = new FindingSink(new TxDoctorProperties());
        for (int i = 0; i < 5; i++) {
            sink.emit(finding("TX_A", "svc.a"));
        }
        assertEquals(1, sink.findings().size());
        assertEquals(5L, sink.occurrenceCounts()
                .get(sink.findings().get(0).dedupKey()));
    }

    @Test
    void distinctKeysAreKeptSeparately() {
        var sink = new FindingSink(new TxDoctorProperties());
        sink.emit(finding("TX_A", "svc.a"));
        sink.emit(finding("TX_A", "svc.b"));
        sink.emit(finding("TX_B", "svc.a"));
        assertEquals(3, sink.findings().size());
    }

    @Test
    void disabledRuleIsNotRecorded() {
        var props = new TxDoctorProperties();
        var toggle = new TxDoctorProperties.RuleToggle();
        toggle.setEnabled(false);
        props.getRules().put("TX_A", toggle);
        var sink = new FindingSink(props);
        sink.emit(finding("TX_A", "svc.a"));
        assertTrue(sink.findings().isEmpty());
    }

    @Test
    void entryMapIsBoundedUnderDistinctKeyFlood() {
        var props = new TxDoctorProperties();
        props.getObserve().setBufferSize(64);
        var sink = new FindingSink(props);
        for (int i = 0; i < 10_000; i++) {
            sink.emit(finding("TX_A", "svc.m" + i));
        }
        // dedup map capped at 4 * capacity — never grows without bound
        assertTrue(sink.findings().size() <= 64 * 4);
    }

    @Test
    void eventBufferIsBounded() {
        var props = new TxDoctorProperties();
        props.getObserve().setBufferSize(64);
        var sink = new FindingSink(props);
        for (int i = 0; i < 5000; i++) {
            sink.recordEvent("evt-" + i);
        }
        assertTrue(sink.events().size() <= 64);
    }

    @Test
    void logForgingCharsAreEscapedInReports() {
        var sink = new FindingSink(new TxDoctorProperties());
        sink.emit(TxFinding.rule("TX_A", Severity.WARNING, Confidence.MEASURED)
                .transaction("svc.a\nINJECTED\tline")
                .message("msg").build());
        // model keeps the raw value; the report layer must escape it
        String json = io.github.adeshsuryan.springtxdoctor.report
                .JsonReportWriter.write("t", sink.findings(),
                        sink.occurrenceCounts(), sink.events());
        assertTrue(json.contains("svc.a\\nINJECTED"),
                "raw newline must never reach the report");
    }
}
