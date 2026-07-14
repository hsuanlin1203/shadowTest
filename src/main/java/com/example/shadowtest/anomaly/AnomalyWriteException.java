package com.example.shadowtest.anomaly;

public class AnomalyWriteException extends RuntimeException {
    public AnomalyWriteException(String message) { super(message); }
    public AnomalyWriteException(String message, Throwable cause) { super(message, cause); }
}
