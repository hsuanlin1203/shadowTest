package com.example.shadowtest.job;

import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.config.ComparisonTaskProperties;
import com.example.shadowtest.engine.ComparisonEngine;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComparisonJobManagerTest {

    private ComparisonTaskProperties propsWithTaskA() {
        ComparisonTaskConfig cfg = new ComparisonTaskConfig();
        cfg.setProduct("alpha");
        ComparisonTaskProperties props = new ComparisonTaskProperties();
        props.setTasks(Map.of("task-a", cfg));
        return props;
    }

    @Test
    void unknownTaskIdRejected() {
        ComparisonEngine engine = Mockito.mock(ComparisonEngine.class);
        ComparisonJobManager manager = new ComparisonJobManager(propsWithTaskA(), engine);
        assertThatThrownBy(() -> manager.trigger("nope"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void triggerRegistersJobAndDelegatesToEngine() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        ComparisonEngine engine = Mockito.mock(ComparisonEngine.class);
        Mockito.doAnswer(inv -> { started.countDown(); return null; })
            .when(engine).run(Mockito.anyString(), Mockito.eq("task-a"), Mockito.any(), Mockito.any());

        ComparisonJobManager manager = new ComparisonJobManager(propsWithTaskA(), engine);
        String jobId = manager.trigger("task-a");

        assertThat(jobId).isNotBlank();
        assertThat(manager.getJob(jobId)).isPresent();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void duplicateTaskIdRejectedWhileActive() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ComparisonEngine engine = Mockito.mock(ComparisonEngine.class);
        Mockito.doAnswer(inv -> { release.await(); return null; })
            .when(engine).run(Mockito.anyString(), Mockito.eq("task-a"), Mockito.any(), Mockito.any());

        ComparisonJobManager manager = new ComparisonJobManager(propsWithTaskA(), engine);
        manager.trigger("task-a");
        try {
            assertThatThrownBy(() -> manager.trigger("task-a"))
                .isInstanceOf(DuplicateTaskException.class);
        } finally {
            release.countDown();
        }
    }
}
