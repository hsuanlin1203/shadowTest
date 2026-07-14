package com.example.shadowtest.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class ElasticsearchTestBase {

    protected static ElasticsearchContainer container;
    protected static ElasticsearchClient client;
    protected static RestClient restClient;

    @BeforeAll
    static void startEs() {
        container = new ElasticsearchContainer(
                DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.0"))
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node");
        container.start();
        restClient = RestClient.builder(HttpHost.create(container.getHttpHostAddress())).build();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper)));
    }

    @AfterAll
    static void stopEs() throws Exception {
        if (restClient != null) restClient.close();
        if (container != null) container.stop();
    }
}
