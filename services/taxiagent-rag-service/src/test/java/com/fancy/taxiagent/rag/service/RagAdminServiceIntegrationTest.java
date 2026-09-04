package com.fancy.taxiagent.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fancy.taxiagent.rag.domain.dto.RagQAQueryVO;
import com.fancy.taxiagent.rag.domain.vo.PageResult;
import com.fancy.taxiagent.rag.embedding.DashScopeEmbeddingClient;
import com.redis.testcontainers.RedisContainer;
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
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 知识库管理集成测试（真实 MySQL/ES/Redis 容器 + mock embedding）。
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
class RagAdminServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_rag");

    @Container
    @ServiceConnection
    static final ElasticsearchContainer ES = new ElasticsearchContainer(
            DockerImageName.parse("taxiagent-elasticsearch:8.18.8-ik")
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private RagAdminService ragAdminService;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private com.fancy.taxiagent.rag.mapper.QaInfoMapper qaInfoMapper;

    @Autowired
    private com.fancy.taxiagent.rag.mapper.QaElasticMapMapper qaElasticMapMapper;

    @MockitoBean
    private DashScopeEmbeddingClient embeddingClient;

    @BeforeEach
    void setUp() throws Exception {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        when(embeddingClient.embed(any())).thenReturn(vector);
        qaElasticMapMapper.delete(null);
        qaInfoMapper.delete(null);
        esClient.deleteByQuery(d -> d.index("qa_knowledge_base")
                .query(q -> q.matchAll(m -> m))
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                .refresh(true));
    }

    @Test
    void shouldAddQaAndSearchHit() throws Exception {
        ragAdminService.addQA(List.of("怎么开电子发票", "电子发票如何申请"), "发票：在订单详情页申请");

        PageResult<RagQAQueryVO> page = ragAdminService.queryPage(1, 10, null);
        assertThat(page.total()).isEqualTo(1);
        RagQAQueryVO vo = page.records().get(0);
        assertThat(vo.questionMap()).hasSize(2);
        assertThat(vo.answer()).isEqualTo("发票：在订单详情页申请");
        assertThat(existsInEs(vo.questionMap().keySet().iterator().next())).isTrue();
    }

    @Test
    void shouldUpdateAnswerAndReflect() throws Exception {
        ragAdminService.addQA(List.of("取消订单会收费吗"), "取消规则：未接单免费");
        String groupId = ragAdminService.queryPage(1, 10, null).records().get(0).groupId();

        ragAdminService.updateAnswer(groupId, "取消规则：司机接单后取消收费");

        assertThat(ragAdminService.queryPage(1, 10, groupId).records().get(0).answer())
                .isEqualTo("取消规则：司机接单后取消收费");
    }

    @Test
    void shouldUpdateQuestionAndReflect() throws Exception {
        ragAdminService.addQA(List.of("旧问题"), "答案");
        String questionId = ragAdminService.queryPage(1, 10, null).records().get(0)
                .questionMap().keySet().iterator().next();

        ragAdminService.updateQuestion(questionId, "新问题");

        assertThat(ragAdminService.queryPage(1, 10, null).records().get(0)
                .questionMap().containsValue("新问题")).isTrue();
    }

    @Test
    void shouldDeleteQaAndRemoveFromEs() throws Exception {
        ragAdminService.addQA(List.of("要删除的问题"), "答案");
        String groupId = ragAdminService.queryPage(1, 10, null).records().get(0).groupId();

        ragAdminService.deleteQA(List.of(groupId), null);

        assertThat(ragAdminService.queryPage(1, 10, null).total()).isZero();
        assertThat(esClient.search(s -> s.index("qa_knowledge_base").size(10), Object.class)
                .hits().hits()).isEmpty();
    }

    @Test
    void shouldRejectEmptyQuestions() {
        assertThatThrownBy(() -> ragAdminService.addQA(List.of(), "答案"))
                .isInstanceOf(com.fancy.taxiagent.rag.exception.InvalidRagRequestException.class);
    }

    private boolean existsInEs(String docId) throws Exception {
        return esClient.get(g -> g.index("qa_knowledge_base").id(docId), Object.class).found();
    }
}
