package com.fancy.taxiagent.rag.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等创建 qa_knowledge_base 索引（dense_vector + ik_smart，knn 检索必需）。
 */
@Component
public class EsIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EsIndexInitializer.class);
    private static final String INDEX = "qa_knowledge_base";

    private final ElasticsearchClient client;

    public EsIndexInitializer(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean exists = client.indices().exists(ExistsRequest.of(e -> e.index(INDEX))).value();
        if (exists) {
            log.info("event=rag_es_index_exists index={}", INDEX);
            return;
        }
        client.indices().create(c -> c
                .index(INDEX)
                .settings(new IndexSettings.Builder().numberOfShards("1").numberOfReplicas("0").build())
                .mappings(m -> m
                        .properties("groupId", p -> p.keyword(k -> k))
                        .properties("question", p -> p.text(t -> t
                                .analyzer("ik_smart")
                                .searchAnalyzer("ik_smart")))
                        .properties("questionVector", p -> p.denseVector(dv -> dv
                                .dims(1024)
                                .index(true)
                                .similarity(co.elastic.clients.elasticsearch._types.mapping
                                        .DenseVectorSimilarity.Cosine)))
                        .properties("answer", p -> p.text(t -> t.index(false)))
                ));
        log.info("event=rag_es_index_created index={}", INDEX);
    }
}
