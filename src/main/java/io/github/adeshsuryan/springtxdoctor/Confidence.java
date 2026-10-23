package io.github.adeshsuryan.springtxdoctor;

/**
 * How strongly the evidence supports a {@link TxFinding}.
 * Never report a heuristic as a proven root cause.
 */
public enum Confidence {
    /** Directly observed fact (attribute, bean state, manager call). */
    MEASURED,
    /** Pattern strongly implies the issue; verification advised. */
    SUSPECTED,
    /** Derived indirectly; treat as a lead, not a conclusion. */
    INFERRED
}
