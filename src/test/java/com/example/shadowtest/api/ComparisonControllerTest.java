package com.example.shadowtest.api;

import com.example.shadowtest.job.ComparisonJobManager;
import com.example.shadowtest.job.DuplicateTaskException;
import com.example.shadowtest.job.JobState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComparisonController.class)
class ComparisonControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ComparisonJobManager manager;

    @Test
    void triggerReturns202WithJobId() throws Exception {
        Mockito.when(manager.trigger("task-a")).thenReturn("job-123");
        mvc.perform(post("/comparisons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"task-a\"}"))
           .andExpect(status().isAccepted())
           .andExpect(jsonPath("$.jobId").value("job-123"));
    }

    @Test
    void unknownTaskReturns400() throws Exception {
        Mockito.when(manager.trigger("nope")).thenThrow(new IllegalArgumentException("unknown"));
        mvc.perform(post("/comparisons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"nope\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateReturns409() throws Exception {
        Mockito.when(manager.trigger("task-a")).thenThrow(new DuplicateTaskException("task-a"));
        mvc.perform(post("/comparisons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"task-a\"}"))
           .andExpect(status().isConflict());
    }

    @Test
    void statusReturns200() throws Exception {
        JobState state = new JobState("job-123", "task-a", "alpha");
        Mockito.when(manager.getJob("job-123")).thenReturn(Optional.of(state));
        mvc.perform(get("/comparisons/job-123"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.jobId").value("job-123"))
           .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void unknownJobReturns404() throws Exception {
        Mockito.when(manager.getJob("missing")).thenReturn(Optional.empty());
        mvc.perform(get("/comparisons/missing")).andExpect(status().isNotFound());
    }
}
