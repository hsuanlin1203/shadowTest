package com.example.shadowtest.api;

import com.example.shadowtest.job.JobState;

public record JobStatusResponse(
        String jobId,
        String taskId,
        String product,
        String status,
        int currentWindow,
        int totalWindows,
        int matched,
        int mismatch,
        int unmatched,
        String error) {

    public static JobStatusResponse from(JobState s) {
        return new JobStatusResponse(
            s.getJobId(), s.getTaskId(), s.getProduct(), s.getStatus().name(),
            s.getCurrentWindow(), s.getTotalWindows(),
            s.getMatched(), s.getMismatch(), s.getUnmatched(), s.getError());
    }
}
