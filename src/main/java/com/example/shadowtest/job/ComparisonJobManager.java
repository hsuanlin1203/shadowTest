package com.example.shadowtest.job;

import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.config.ComparisonTaskProperties;
import com.example.shadowtest.engine.ComparisonEngine;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

@Service
public class ComparisonJobManager {

    private final ComparisonTaskProperties properties;
    private final ComparisonEngine engine;
    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> activeTaskIds = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ComparisonJobManager(ComparisonTaskProperties properties, ComparisonEngine engine) {
        this.properties = properties;
        this.engine = engine;
    }

    public String trigger(String taskId) {
        ComparisonTaskConfig cfg = properties.getTasks().get(taskId);
        if (cfg == null) {
            throw new IllegalArgumentException("unknown task id: " + taskId);
        }
        if (activeTaskIds.putIfAbsent(taskId, Boolean.TRUE) != null) {
            throw new DuplicateTaskException(taskId);
        }

        String jobId = UUID.randomUUID().toString();
        JobState state = new JobState(jobId, taskId, cfg.getProduct());
        jobs.put(jobId, state);

        executor.submit(() -> {
            try {
                state.setStatus(JobStatus.RUNNING);
                engine.run(jobId, taskId, cfg, state);
                state.setStatus(JobStatus.COMPLETED);
            } catch (Exception e) {
                state.setStatus(JobStatus.FAILED);
                state.setError(e.getMessage());
            } finally {
                state.setFinishedAt(Instant.now());
                activeTaskIds.remove(taskId);
            }
        });
        return jobId;
    }

    public Optional<JobState> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
