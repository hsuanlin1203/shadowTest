package com.example.shadowtest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class ShadowTestApplicationTests {

    @MockBean
    ElasticsearchClient elasticsearchClient;

    @Test
    void contextLoads() {
    }
}
