package com.example.shadowtest.api;

import com.example.shadowtest.job.ComparisonJobManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/comparisons")
public class ComparisonController {

    private final ComparisonJobManager manager;

    public ComparisonController(ComparisonJobManager manager) {
        this.manager = manager;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> trigger(@Valid @RequestBody TriggerRequest request) {
        String jobId = manager.trigger(request.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", jobId));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> status(@PathVariable String jobId) {
        return manager.getJob(jobId)
            .map(JobStatusResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
