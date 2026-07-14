package com.example.shadowtest.job;

public class DuplicateTaskException extends RuntimeException {
    public DuplicateTaskException(String taskId) {
        super("comparison already running for task id: " + taskId);
    }
}
