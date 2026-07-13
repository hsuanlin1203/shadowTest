package com.example.shadowtest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "elasticsearch.host=localhost",
    "elasticsearch.port=9200",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration"
})
class ShadowTestApplicationTests {
    @Test
    void contextLoads() {
    }
}
