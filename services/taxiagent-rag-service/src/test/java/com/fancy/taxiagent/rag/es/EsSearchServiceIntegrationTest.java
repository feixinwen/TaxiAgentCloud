package com.fancy.taxiagent.rag.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fancy.taxiagent.rag.domain.dto.RagSearchResult;
import com.fancy.taxiagent.rag.embedding.DashScopeEmbeddingClient;
import com.fancy.taxiagent.rag.service.RagSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 混合检索集成测试（真实 ES 容器 + 确定性 mock embedding）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false"
        }
)
class EsSearchServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_rag");

    @Container
    @ServiceConnection
    static final ElasticsearchContainer ES = new ElasticsearchContainer(
            org.testcontainers.utility.DockerImageName
                    .parse("taxiagent-elasticsearch:8.18.8-ik")
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EsSearchService esSearchService;

    @Autowired
    private RagSearchService ragSearchService;

    @MockitoBean
    private DashScopeEmbeddingClient embeddingClient;

    @BeforeEach
    void setUp() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        when(embeddingClient.embed(any())).thenReturn(vector);
    }

    @Test
    void shouldSearchByBm25AndReturnOrderedHits() throws Exception {
        indexDoc("doc1", "g1", "网约车订单退款流程是什么", "退款流程：联系客服提交工单");
        indexDoc("doc2", "g2", "电子发票怎么开具", "发票开具：在订单详情页申请");

        Map<String, Map<String, Object>> hits = esSearchService.bm25Search("退款", 10);

        assertThat(hits.keySet()).contains("doc1");
        assertThat(hits.get("doc1").get("groupId")).isEqualTo("g1");
        assertThat(hits.get("doc1").get("answer")).isEqualTo("退款流程：联系客服提交工单");
    }

    @Test
    void shouldSearchByKnnAndFuseWithBm25() throws Exception {
        indexDoc("docA", "g1", "取消订单会收费吗", "取消规则：司机接单后取消可能收费");
        indexDoc("docB", "g2", "司机绕路怎么办", "绕路处理：提交费用复核工单");
        indexDoc("docC", "g3", "高速费谁承担", "高速费：按实际行程由乘客承担");

        // mock 向量全等 → knn 全命中；bm25 命中「取消」
        List<RagSearchResult> results = ragSearchService.search("取消订单", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).groupId()).isEqualTo("g1");
        assertThat(results.get(0).answer()).contains("取消");
    }

    @Test
    void shouldDeduplicateSameGroup() throws Exception {
        indexDoc("d1", "g1", "怎么开电子发票", "发票：订单详情页申请");
        indexDoc("d2", "g1", "电子发票怎么开", "发票：订单详情页申请");

        List<RagSearchResult> results = ragSearchService.search("发票", 5);

        assertThat(results.stream().map(RagSearchResult::groupId).distinct().count())
                .isEqualTo(results.size());
    }

    private void indexDoc(String id, String groupId, String question, String answer) throws Exception {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        co.elastic.clients.elasticsearch.core.IndexRequest<QaDocument> request =
                co.elastic.clients.elasticsearch.core.IndexRequest.of(i -> i
                        .index("qa_knowledge_base").id(id)
                        .document(new QaDocument(id, groupId, question, vector, answer))
                        .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
        esClient.index(request);
    }
}
