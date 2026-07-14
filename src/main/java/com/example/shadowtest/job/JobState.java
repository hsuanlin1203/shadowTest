package com.example.shadowtest.job;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class JobState {
    private final String jobId;
    private final String taskId;
    private final String product;
    private volatile JobStatus status = JobStatus.PENDING;
    private final AtomicInteger matched = new AtomicInteger();
    private final AtomicInteger mismatch = new AtomicInteger();
    private final AtomicInteger unmatched = new AtomicInteger();
    private final AtomicInteger currentWindow = new AtomicInteger();
    private volatile int totalWindows;
    private volatile String error;
    private final Instant startedAt = Instant.now();
    private volatile Instant finishedAt;

    public JobState(String jobId, String taskId, String product) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.product = product;
    }

    public String getJobId() { return jobId; }
    public String getTaskId() { return taskId; }
    public String getProduct() { return product; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getMatched() { return matched.get(); }
    public void incMatched() { matched.incrementAndGet(); }
    public int getMismatch() { return mismatch.get(); }
    public void incMismatch() { mismatch.incrementAndGet(); }
    public int getUnmatched() { return unmatched.get(); }
    public void addUnmatched(int n) { unmatched.addAndGet(n); }
    public int getCurrentWindow() { return currentWindow.get(); }
    public void incCurrentWindow() { currentWindow.incrementAndGet(); }
    public int getTotalWindows() { return totalWindows; }
    public void setTotalWindows(int totalWindows) { this.totalWindows = totalWindows; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
