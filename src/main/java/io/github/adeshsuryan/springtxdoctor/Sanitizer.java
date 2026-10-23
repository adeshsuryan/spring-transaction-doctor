package io.github.adeshsuryan.springtxdoctor;

/**
 * Sanitizes externally-originated strings (transaction names set
 * programmatically, bean names, class names) before they reach logs or
 * reports. Strips control characters — including CR/LF, which would
 * otherwise allow log forging — and caps length.
 */
public final class Sanitizer {

    private static final int MAX_LENGTH = 200;

    private Sanitizer() {
    }

    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
        for (int i = 0; i < value.length() && sb.length() < MAX_LENGTH; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        if (value.length() > MAX_LENGTH) {
            sb.append("…");
        }
        return sb.toString();
    }
}
