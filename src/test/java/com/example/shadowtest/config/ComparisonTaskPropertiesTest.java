package com.example.shadowtest.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

    // TestApp's component scan (scoped to this class's package) also picks up the sibling
    // ElasticsearchClientConfig, so the real bean must be mocked here too now that the bean is
    // eagerly created (no @Lazy) - see ShadowTestApplicationTests for the same pattern.
    @MockBean
    ElasticsearchClient elasticsearchClient;

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
