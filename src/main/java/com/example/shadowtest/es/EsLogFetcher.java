package com.example.shadowtest.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.engine.TimeWindow;
import com.example.shadowtest.model.LogRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fetches all records for a host within a time window using PIT + search_after. */
@Component
public class EsLogFetcher {

    private final ElasticsearchClient client;

    public EsLogFetcher(ElasticsearchClient client) {
        this.client = client;
    }

    public List<LogRecord> fetch(String index, ComparisonTaskConfig cfg, TimeWindow window) {
        try {
            return doFetch(index, cfg, window);
        } catch (IOException e) {
            throw new EsFetchException("failed to fetch from index " + index, e);
        }
    }

    private List<LogRecord> doFetch(String index, ComparisonTaskConfig cfg, TimeWindow window) throws IOException {
        OpenPointInTimeResponse pit = client.openPointInTime(p -> p
            .index(index)
            .keepAlive(k -> k.time("1m")));
        String pitId = pit.id();

        List<LogRecord> results = new ArrayList<>();
        try {
            Query query = Query.of(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field(cfg.getHostField()).value(cfg.getHost())))
                .filter(f -> f.range(r -> r.date(d -> d
                    .field(cfg.getTimeField())
                    .gte(window.start().toString())
                    .lt(window.end().toString()))))));

            List<FieldValue> searchAfter = null;
            while (true) {
                final List<FieldValue> after = searchAfter;
                SearchRequest req = SearchRequest.of(s -> {
                    s.size(cfg.getPageSize())
                     .query(query)
                     .pit(p -> p.id(pitId).keepAlive(k -> k.time("1m")))
                     .sort(so -> so.field(f -> f.field(cfg.getTimeField()).order(SortOrder.Asc)))
                     .sort(so -> so.field(f -> f.field("_shard_doc").order(SortOrder.Asc)));
                    if (after != null) {
                        s.searchAfter(after);
                    }
                    return s;
                });

                @SuppressWarnings("rawtypes")
                SearchResponse<Map> resp = client.search(req, Map.class);
                List<Hit<Map>> hits = resp.hits().hits();
                if (hits.isEmpty()) {
                    break;
                }
                for (Hit<Map> hit : hits) {
                    results.add(toRecord(index, cfg, hit));
                }
                searchAfter = hits.get(hits.size() - 1).sort();
                if (hits.size() < cfg.getPageSize()) {
                    break;
                }
            }
        } finally {
            final String id = pitId;
            client.closePointInTime(c -> c.id(id));
        }
        return results;
    }

    @SuppressWarnings("rawtypes")
    private LogRecord toRecord(String index, ComparisonTaskConfig cfg, Hit<Map> hit) {
        Map source = hit.source();
        String traceId = asString(source.get(cfg.getTraceIdField()));
        String host = asString(source.get(cfg.getHostField()));
        String body = asString(source.get(cfg.getBodyField()));
        Instant ts = Instant.parse(asString(source.get(cfg.getTimeField())));
        return new LogRecord(traceId, host, ts, hit.id(), index, body, cfg.getProduct());
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
