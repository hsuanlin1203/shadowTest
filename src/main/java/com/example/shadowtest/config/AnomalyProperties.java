package com.example.shadowtest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "anomaly")
public class AnomalyProperties {
    private String indexName = "shadow-test-anomalies";
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
}
