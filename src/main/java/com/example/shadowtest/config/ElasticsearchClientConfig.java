package com.example.shadowtest.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class ElasticsearchClientConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(
            @Value("${elasticsearch.host}") String host,
            @Value("${elasticsearch.port}") int port,
            @Value("${elasticsearch.scheme:http}") String scheme,
            @Value("${elasticsearch.username:}") String username,
            @Value("${elasticsearch.password:}") String password) {

        var builder = RestClient.builder(new HttpHost(host, port, scheme));
        if (StringUtils.hasText(username)) {
            var creds = new BasicCredentialsProvider();
            creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(cb -> cb.setDefaultCredentialsProvider(creds));
        }
        RestClient restClient = builder.build();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
        return new ElasticsearchClient(transport);
    }
}
