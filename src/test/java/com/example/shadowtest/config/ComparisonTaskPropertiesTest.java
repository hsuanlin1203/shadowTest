package com.example.shadowtest.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ComparisonTaskPropertiesTest.TestApp.class)
@ActiveProfiles("configtest")
class ComparisonTaskPropertiesTest {

    @SpringBootApplication
    @EnableConfigurationProperties(ComparisonTaskProperties.class)
    static class TestApp {}

    @Autowired
    ComparisonTaskProperties properties;

    @Test
    void bindsTasksByIdWithDefaults() {
        ComparisonTaskConfig task = properties.getTasks().get("task-a");
        assertThat(task).isNotNull();
        assertThat(task.getProduct()).isEqualTo("alpha");
        assertThat(task.getProdIndex()).isEqualTo("prod-a");
        assertThat(task.getShadowIndex()).isEqualTo("shadow-a");
        assertThat(task.getHost()).isEqualTo("a.example.com");
        assertThat(task.getTraceIdField()).isEqualTo("traceId");
        assertThat(task.getBodyField()).isEqualTo("responseBody");
        assertThat(task.getWindowMinutes()).isEqualTo(5);
        assertThat(task.getPageSize()).isEqualTo(100);
    }
}
