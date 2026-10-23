package io.github.adeshsuryan.springtxdoctor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SanitizerTest {

    @Test
    void stripsNewlinesAndControls() {
        assertEquals("Service.pay__forged", Sanitizer.clean("Service.pay\n\rforged"));
        assertEquals("a_b", Sanitizer.clean("a\tb"));
    }

    @Test
    void capsLength() {
        String long_ = "x".repeat(500);
        String out = Sanitizer.clean(long_);
        assertTrue(out.length() <= 201);
    }

    @Test
    void nullSafe() {
        assertNull(Sanitizer.clean(null));
    }
}
