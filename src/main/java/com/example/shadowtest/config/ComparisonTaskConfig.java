package com.example.shadowtest.config;

public class ComparisonTaskConfig {
    private String product;
    private String prodIndex;
    private String shadowIndex;
    private String host;
    private String hostField = "host";
    private String timeField = "@timestamp";
    private String traceIdField = "traceId";
    private String bodyField = "responseBody";
    private int windowMinutes = 10;
    private int pageSize = 500;
    private String startTime; // ISO-8601, e.g. 2026-07-13T00:00:00Z
    private String endTime;

    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public String getProdIndex() { return prodIndex; }
    public void setProdIndex(String prodIndex) { this.prodIndex = prodIndex; }
    public String getShadowIndex() { return shadowIndex; }
    public void setShadowIndex(String shadowIndex) { this.shadowIndex = shadowIndex; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getHostField() { return hostField; }
    public void setHostField(String hostField) { this.hostField = hostField; }
    public String getTimeField() { return timeField; }
    public void setTimeField(String timeField) { this.timeField = timeField; }
    public String getTraceIdField() { return traceIdField; }
    public void setTraceIdField(String traceIdField) { this.traceIdField = traceIdField; }
    public String getBodyField() { return bodyField; }
    public void setBodyField(String bodyField) { this.bodyField = bodyField; }
    public int getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
}
