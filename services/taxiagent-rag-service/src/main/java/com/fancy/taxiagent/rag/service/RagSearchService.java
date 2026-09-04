package com.fancy.taxiagent.rag.service;

import com.fancy.taxiagent.rag.domain.dto.RagSearchResult;
import com.fancy.taxiagent.rag.embedding.DashScopeEmbeddingClient;
import com.fancy.taxiagent.rag.es.EsSearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 混合检索：embedding → 并行 knn + BM25 → RRF 融合 → groupId 去重 → topK。
 */
@Service
public class RagSearchService {

    private static final int RRF_K = 60;
    private static final int WINDOW_SIZE = 100;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;

    private final DashScopeEmbeddingClient embeddingClient;
    private final EsSearchService esSearchService;

    public RagSearchService(DashScopeEmbeddingClient embeddingClient, EsSearchService esSearchService) {
        this.embeddingClient = embeddingClient;
        this.esSearchService = esSearchService;
    }

    public List<RagSearchResult> search(String question, Integer topK) {
        int top = topK == null || topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        float[] vector = embeddingClient.embed(question);

        CompletableFuture<Map<String, Map<String, Object>>> knnFuture =
                CompletableFuture.supplyAsync(() -> esSearchService.knnSearch(vector, WINDOW_SIZE));
        CompletableFuture<Map<String, Map<String, Object>>> bm25Future =
                CompletableFuture.supplyAsync(() -> esSearchService.bm25Search(question, WINDOW_SIZE));
        CompletableFuture.allOf(knnFuture, bm25Future).join();

        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, Map<String, Object>> docMap = new HashMap<>();
        rrfAdd(knnFuture.join(), scoreMap, docMap);
        rrfAdd(bm25Future.join(), scoreMap, docMap);

        List<String> ranked = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        List<RagSearchResult> results = new ArrayList<>();
        Set<String> seenGroupIds = new HashSet<>();
        for (String docId : ranked) {
            Map<String, Object> source = docMap.get(docId);
            String groupId = String.valueOf(source.get("groupId"));
            if (!seenGroupIds.add(groupId)) {
                continue;
            }
            results.add(new RagSearchResult(
                    groupId,
                    String.valueOf(source.get("question")),
                    String.valueOf(source.get("answer"))));
            if (results.size() >= top) {
                break;
            }
        }
        return results;
    }

    private void rrfAdd(Map<String, Map<String, Object>> hits,
                        Map<String, Double> scoreMap,
                        Map<String, Map<String, Object>> docMap) {
        int rank = 0;
        for (Map.Entry<String, Map<String, Object>> entry : hits.entrySet()) {
            double score = 1.0 / (RRF_K + (rank + 1));
            scoreMap.merge(entry.getKey(), score, Double::sum);
            docMap.putIfAbsent(entry.getKey(), entry.getValue());
            rank++;
        }
    }
}
