package com.example.shadowtest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the bug where {@code ElasticsearchClientConfig} built its
 * {@code JacksonJsonpMapper} with a bare {@code new ObjectMapper()} (no JSR-310 module),
 * which threw {@code InvalidDefinitionException} for any {@link Instant} field - e.g. the
 * {@code windowStart}/{@code windowEnd}/{@code detectedAt} fields on
 * {@link com.example.shadowtest.anomaly.Anomaly}.
 *
 * <p>This does not exercise {@code ElasticsearchClientConfig}'s bean directly (that needs a real
 * Elasticsearch connection, which is blocked by this machine's Selector limitation). It instead
 * proves the underlying serialization mechanism the fix relies on: an {@link ObjectMapper} with
 * {@link JavaTimeModule} registered - the exact construction now used in
 * {@code ElasticsearchClientConfig.elasticsearchClient(...)} - can serialize an {@link Instant}
 * without throwing.
 */
class ElasticsearchObjectMapperTest {

    @Test
    void objectMapperSerializesInstant() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        String json = mapper.writeValueAsString(Instant.parse("2026-07-13T00:00:00Z"));

        assertThat(json).isNotBlank();
    }

    @Test
    void bareObjectMapperFailsToSerializeInstantWithoutModule() {
        ObjectMapper bareMapper = new ObjectMapper();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                bareMapper.writeValueAsString(Instant.parse("2026-07-13T00:00:00Z"))))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidDefinitionException.class);
    }
}
