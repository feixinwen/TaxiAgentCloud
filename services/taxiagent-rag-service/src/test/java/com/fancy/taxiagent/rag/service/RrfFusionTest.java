package com.fancy.taxiagent.rag.service;

import com.fancy.taxiagent.rag.domain.dto.RagSearchResult;
import com.fancy.taxiagent.rag.embedding.DashScopeEmbeddingClient;
import com.fancy.taxiagent.rag.es.EsSearchService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RrfFusionTest {

    @Test
    void shouldFuseTwoRankingsWithRrf() {
        DashScopeEmbeddingClient embedding = mock(DashScopeEmbeddingClient.class);
        EsSearchService es = mock(EsSearchService.class);
        when(embedding.embed(any())).thenReturn(new float[1024]);

        // knn 命中序：docA(rank0), docB(rank1), docC(rank2)
        Map<String, Map<String, Object>> knn = new LinkedHashMap<>();
        knn.put("docA", source("g1", "问题A", "答案A"));
        knn.put("docB", source("g2", "问题B", "答案B"));
        knn.put("docC", source("g3", "问题C", "答案C"));
        // bm25 命中序：docB(rank0), docA(rank1)
        Map<String, Map<String, Object>> bm25 = new LinkedHashMap<>();
        bm25.put("docB", source("g2", "问题B", "答案B"));
        bm25.put("docA", source("g1", "问题A", "答案A"));
        when(es.knnSearch(any(float[].class), anyInt())).thenReturn(knn);
        when(es.bm25Search(any(), anyInt())).thenReturn(bm25);

        RagSearchService service = new RagSearchService(embedding, es);
        List<RagSearchResult> results = service.search("测试", 5);

        // docB 双队列命中（1/61 + 1/62 最高），docA 次之（1/61 + 1/62 同分但 docB 先被 merge？）
        // docB: 1/(60+1) + 1/(60+1) = 0.03279；docA: 1/(60+1) + 1/(60+2) = 0.03226
        assertThat(results).hasSize(3);
        assertThat(results.get(0).groupId()).isEqualTo("g2");
        assertThat(results.get(1).groupId()).isEqualTo("g1");
    }

    @Test
    void shouldDeduplicateByGroupId() {
        DashScopeEmbeddingClient embedding = mock(DashScopeEmbeddingClient.class);
        EsSearchService es = mock(EsSearchService.class);
        when(embedding.embed(any())).thenReturn(new float[1024]);

        // 同组 g1 的两个文档 docA、docB 都在 knn 前列
        Map<String, Map<String, Object>> knn = new LinkedHashMap<>();
        knn.put("docA", source("g1", "问题A1", "答案1"));
        knn.put("docB", source("g1", "问题A2", "答案1"));
        knn.put("docC", source("g2", "问题B", "答案B"));
        Map<String, Map<String, Object>> bm25 = new LinkedHashMap<>();
        when(es.knnSearch(any(float[].class), anyInt())).thenReturn(knn);
        when(es.bm25Search(any(), anyInt())).thenReturn(bm25);

        RagSearchService service = new RagSearchService(embedding, es);
        List<RagSearchResult> results = service.search("测试", 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).groupId()).isEqualTo("g1");
        assertThat(results.get(1).groupId()).isEqualTo("g2");
    }

    @Test
    void shouldRespectTopK() {
        DashScopeEmbeddingClient embedding = mock(DashScopeEmbeddingClient.class);
        EsSearchService es = mock(EsSearchService.class);
        when(embedding.embed(any())).thenReturn(new float[1024]);

        Map<String, Map<String, Object>> knn = new LinkedHashMap<>();
        knn.put("docA", source("g1", "q", "a"));
        knn.put("docB", source("g2", "q", "a"));
        knn.put("docC", source("g3", "q", "a"));
        Map<String, Map<String, Object>> bm25 = new LinkedHashMap<>();
        when(es.knnSearch(any(float[].class), anyInt())).thenReturn(knn);
        when(es.bm25Search(any(), anyInt())).thenReturn(bm25);

        RagSearchService service = new RagSearchService(embedding, es);
        List<RagSearchResult> results = service.search("测试", 2);

        assertThat(results).hasSize(2);
    }

    private Map<String, Object> source(String groupId, String question, String answer) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", "doc");
        map.put("groupId", groupId);
        map.put("question", question);
        map.put("answer", answer);
        return map;
    }
}
