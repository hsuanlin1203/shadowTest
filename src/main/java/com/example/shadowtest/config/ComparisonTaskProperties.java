package com.example.shadowtest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "comparison")
public class ComparisonTaskProperties {
    private Map<String, ComparisonTaskConfig> tasks = new LinkedHashMap<>();
    public Map<String, ComparisonTaskConfig> getTasks() { return tasks; }
    public void setTasks(Map<String, ComparisonTaskConfig> tasks) { this.tasks = tasks; }
}
