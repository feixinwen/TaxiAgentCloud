package com.fancy.taxiagent.rag.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 混合检索（knn 向量 + BM25 文本），返回按命中序排列的 docId → source 映射。
 */
@Service
public class EsSearchService {

    private static final String INDEX = "qa_knowledge_base";

    private final ElasticsearchClient client;

    public EsSearchService(ElasticsearchClient client) {
        this.client = client;
    }

    public Map<String, Map<String, Object>> knnSearch(float[] queryVector, int k) {
        try {
            var response = client.search(s -> s
                            .index(INDEX)
                            .knn(knn -> knn
                                    .field("questionVector")
                                    .queryVector(toList(queryVector))
                                    .k(k)
                                    .numCandidates(200))
                            .size(k)
                            .source(so -> so.filter(f -> f.includes("id", "groupId", "question", "answer"))),
                    QaDocument.class);
            return toOrderedMap(response.hits().hits());
        } catch (Exception e) {
            throw new IllegalStateException("ES knn 检索失败", e);
        }
    }

    public Map<String, Map<String, Object>> bm25Search(String query, int size) {
        try {
            var response = client.search(s -> s
                            .index(INDEX)
                            .query(q -> q.match(m -> m.field("question").query(query)))
                            .size(size)
                            .source(so -> so.filter(f -> f.includes("id", "groupId", "question", "answer"))),
                    QaDocument.class);
            return toOrderedMap(response.hits().hits());
        } catch (Exception e) {
            throw new IllegalStateException("ES BM25 检索失败", e);
        }
    }

    private List<Float> toList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }

    private Map<String, Map<String, Object>> toOrderedMap(
            List<co.elastic.clients.elasticsearch.core.search.Hit<QaDocument>> hits) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var hit : hits) {
            QaDocument doc = hit.source();
            if (doc == null) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", doc.getId());
            source.put("groupId", doc.getGroupId());
            source.put("question", doc.getQuestion());
            source.put("answer", doc.getAnswer());
            result.put(doc.getId(), source);
        }
        return result;
    }
}
