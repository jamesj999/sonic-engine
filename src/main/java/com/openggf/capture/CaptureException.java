package com.openggf.capture;

/** Thrown when a capture sink or encoder fails. */
public class CaptureException extends Exception {
    public CaptureException(String message) { super(message); }
    public CaptureException(String message, Throwable cause) { super(message, cause); }
}
