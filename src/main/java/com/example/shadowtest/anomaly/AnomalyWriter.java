package com.example.shadowtest.anomaly;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.example.shadowtest.config.AnomalyProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class AnomalyWriter {

    private final ElasticsearchClient client;
    private final AnomalyProperties props;

    public AnomalyWriter(ElasticsearchClient client, AnomalyProperties props) {
        this.client = client;
        this.props = props;
    }

    public void write(List<Anomaly> anomalies) {
        if (anomalies.isEmpty()) {
            return;
        }
        BulkRequest.Builder br = new BulkRequest.Builder().refresh(Refresh.False);
        for (Anomaly a : anomalies) {
            br.operations(op -> op.index(idx -> idx.index(props.getIndexName()).document(a)));
        }
        try {
            BulkResponse resp = client.bulk(br.build());
            if (resp.errors()) {
                throw new AnomalyWriteException("bulk anomaly write had failures");
            }
        } catch (IOException e) {
            throw new AnomalyWriteException("failed to bulk write anomalies", e);
        }
    }
}
