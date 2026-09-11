package com.openggf.trace;

/** Severity of a single field divergence. */
public enum Severity {
    MATCH,   // No divergence
    WARNING, // Within tolerance but not exact
    ERROR    // Exceeds tolerance
}


