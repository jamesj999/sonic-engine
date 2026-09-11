package com.openggf.configuration;

/** The kind of a configuration value, used to format and validate it. */
public enum ConfigType {
    BOOL,
    INT,
    DOUBLE,
    STRING,
    /** A GLFW key binding, rendered as a key name (e.g. {@code SPACE}). */
    KEY,
    /** A string restricted to a fixed set of allowed values. */
    ENUM
}
