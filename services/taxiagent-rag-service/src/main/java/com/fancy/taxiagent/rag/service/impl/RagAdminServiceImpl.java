package com.fancy.taxiagent.rag.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fancy.taxiagent.rag.domain.dto.RagQAQueryVO;
import com.fancy.taxiagent.rag.domain.entity.QaElasticMap;
import com.fancy.taxiagent.rag.domain.entity.QaInfo;
import com.fancy.taxiagent.rag.domain.vo.PageResult;
import com.fancy.taxiagent.rag.embedding.DashScopeEmbeddingClient;
import com.fancy.taxiagent.rag.es.QaDocument;
import com.fancy.taxiagent.rag.exception.InvalidRagRequestException;
import com.fancy.taxiagent.rag.exception.QaNotFoundException;
import com.fancy.taxiagent.rag.id.IdGenerator;
import com.fancy.taxiagent.rag.mapper.QaElasticMapMapper;
import com.fancy.taxiagent.rag.mapper.QaInfoMapper;
import com.fancy.taxiagent.rag.service.RagAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理服务。写路径照单体：新增先 ES 后 MySQL（MySQL 失败补偿删 ES）；
 * 删除先删 ES 再删 MySQL（保留单体已知不一致窗口，低频管理操作可接受）。
 */
@Service
public class RagAdminServiceImpl implements RagAdminService {

    private static final Logger log = LoggerFactory.getLogger(RagAdminServiceImpl.class);
    private static final String INDEX = "qa_knowledge_base";

    private final QaInfoMapper qaInfoMapper;
    private final QaElasticMapMapper qaElasticMapMapper;
    private final DashScopeEmbeddingClient embeddingClient;
    private final ElasticsearchClient esClient;
    private final IdGenerator idGenerator;

    public RagAdminServiceImpl(QaInfoMapper qaInfoMapper,
                               QaElasticMapMapper qaElasticMapMapper,
                               DashScopeEmbeddingClient embeddingClient,
                               ElasticsearchClient esClient,
                               IdGenerator idGenerator) {
        this.qaInfoMapper = qaInfoMapper;
        this.qaElasticMapMapper = qaElasticMapMapper;
        this.embeddingClient = embeddingClient;
        this.esClient = esClient;
        this.idGenerator = idGenerator;
    }

    @Override
    @Transactional
    public void addQA(List<String> questions, String answer) {
        if (questions == null || questions.isEmpty()) {
            throw new InvalidRagRequestException("问题列表不能为空");
        }
        if (!StringUtils.hasText(answer)) {
            throw new InvalidRagRequestException("答案不能为空");
        }
        List<String> normalized = questions.stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (normalized.isEmpty()) {
            throw new InvalidRagRequestException("问题列表不能为空");
        }

        long groupId = idGenerator.nextId();
        List<String> writtenElasticIds = new ArrayList<>();
        try {
            for (String question : normalized) {
                long elasticId = idGenerator.nextId();
                float[] vector = embeddingClient.embed(question);
                try {
                    esClient.index(i -> i.index(INDEX).id(String.valueOf(elasticId))
                            .document(new QaDocument(String.valueOf(elasticId),
                                    String.valueOf(groupId), question, vector, answer))
                            .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
                    writtenElasticIds.add(String.valueOf(elasticId));
                } catch (Exception e) {
                    throw new IllegalStateException("ES 索引写入失败", e);
                }
                QaElasticMap map = new QaElasticMap();
                map.setElasticId(elasticId);
                map.setGroupId(groupId);
                map.setQuestion(question);
                qaElasticMapMapper.insert(map);
            }
            QaInfo info = new QaInfo();
            info.setGroupId(groupId);
            info.setAnswer(answer);
            qaInfoMapper.insert(info);
        } catch (RuntimeException e) {
            // MySQL 失败（事务回滚）时补偿删除已写入的 ES 文档（best-effort）
            for (String elasticId : writtenElasticIds) {
                try {
                    esClient.delete(d -> d.index(INDEX).id(elasticId));
                } catch (Exception ignored) {
                    log.warn("event=rag_es_compensate_delete_failed elasticId={}", elasticId);
                }
            }
            throw e;
        }
        log.info("event=rag_qa_added groupId={} questions={}", groupId, normalized.size());
    }

    @Override
    @Transactional
    public void addQAs(List<List<String>> questionGroups, List<String> answers) {
        if (questionGroups == null || answers == null || questionGroups.size() != answers.size()) {
            throw new InvalidRagRequestException("批量数据不合法");
        }
        for (int i = 0; i < questionGroups.size(); i++) {
            addQA(questionGroups.get(i), answers.get(i));
        }
    }

    @Override
    @Transactional
    public void deleteQA(List<String> groupIds, List<String> questionIds) {
        List<String> elasticIds = new ArrayList<>();
        if (questionIds != null) {
            elasticIds.addAll(questionIds);
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            List<QaElasticMap> maps = qaElasticMapMapper.selectList(new LambdaQueryWrapper<QaElasticMap>()
                    .in(QaElasticMap::getGroupId, groupIds.stream().map(Long::valueOf).toList()));
            maps.forEach(m -> elasticIds.add(String.valueOf(m.getElasticId())));
        }
        if (elasticIds.isEmpty()) {
            throw new InvalidRagRequestException("未指定要删除的问答");
        }
        for (String elasticId : elasticIds) {
            try {
                esClient.delete(d -> d.index(INDEX).id(elasticId));
            } catch (Exception e) {
                log.warn("event=rag_es_delete_failed elasticId={} error={}", elasticId, e.getMessage());
            }
        }
        try {
            esClient.indices().refresh(r -> r.index(INDEX));
        } catch (Exception e) {
            log.warn("event=rag_es_refresh_failed error={}", e.getMessage());
        }
        if (questionIds != null && !questionIds.isEmpty()) {
            qaElasticMapMapper.delete(new LambdaQueryWrapper<QaElasticMap>()
                    .in(QaElasticMap::getElasticId, questionIds.stream().map(Long::valueOf).toList()));
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            List<Long> gids = groupIds.stream().map(Long::valueOf).toList();
            qaElasticMapMapper.delete(new LambdaQueryWrapper<QaElasticMap>()
                    .in(QaElasticMap::getGroupId, gids));
            for (Long gid : gids) {
                Long remaining = qaElasticMapMapper.selectCount(new LambdaQueryWrapper<QaElasticMap>()
                        .eq(QaElasticMap::getGroupId, gid));
                if (remaining == null || remaining == 0) {
                    qaInfoMapper.delete(new LambdaQueryWrapper<QaInfo>().eq(QaInfo::getGroupId, gid));
                }
            }
        }
        log.info("event=rag_qa_deleted elasticIds={}", elasticIds.size());
    }

    @Override
    @Transactional
    public void updateAnswer(String groupId, String answer) {
        if (!StringUtils.hasText(groupId) || !StringUtils.hasText(answer)) {
            throw new InvalidRagRequestException("groupId 与答案不能为空");
        }
        Long gid = Long.valueOf(groupId);
        QaInfo info = qaInfoMapper.selectOne(new LambdaQueryWrapper<QaInfo>().eq(QaInfo::getGroupId, gid));
        if (info == null) {
            throw new QaNotFoundException("答案组不存在");
        }
        try {
            co.elastic.clients.elasticsearch._types.Script script =
                    co.elastic.clients.elasticsearch._types.Script.of(s -> s
                            .lang("painless")
                            .source("ctx._source.answer = params.answer")
                            .params("answer", co.elastic.clients.json.JsonData.of(answer)));
            UpdateByQueryRequest request = UpdateByQueryRequest.of(u -> u
                    .index(INDEX)
                    .query(q -> q.term(t -> t.field("groupId").value(groupId)))
                    .script(script)
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                    .refresh(true));
            var response = esClient.updateByQuery(request);
            if (response.failures() != null && !response.failures().isEmpty()) {
                throw new IllegalStateException("ES 更新答案存在失败项");
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("ES 更新答案失败", e);
        }
        info.setAnswer(answer);
        qaInfoMapper.updateById(info);
        log.info("event=rag_qa_answer_updated groupId={}", groupId);
    }

    @Override
    @Transactional
    public void updateQuestion(String questionId, String question) {
        if (!StringUtils.hasText(questionId) || !StringUtils.hasText(question)) {
            throw new InvalidRagRequestException("questionId 与问题不能为空");
        }
        Long elasticId = Long.valueOf(questionId);
        QaElasticMap map = qaElasticMapMapper.selectOne(new LambdaQueryWrapper<QaElasticMap>()
                .eq(QaElasticMap::getElasticId, elasticId));
        if (map == null) {
            throw new QaNotFoundException("问题不存在");
        }
        float[] vector = embeddingClient.embed(question.trim());
        try {
            UpdateRequest<QaDocument, Object> request = UpdateRequest.of(u -> u
                    .index(INDEX).id(questionId)
                    .doc(Map.of("question", question.trim(), "questionVector", toList(vector)))
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
            esClient.update(request, QaDocument.class);
        } catch (Exception e) {
            throw new IllegalStateException("ES 更新问题失败", e);
        }
        map.setQuestion(question.trim());
        qaElasticMapMapper.updateById(map);
        log.info("event=rag_qa_question_updated elasticId={}", questionId);
    }

    @Override
    public PageResult<RagQAQueryVO> queryPage(Integer page, Integer size, String groupId) {
        int p = page == null || page <= 0 ? 1 : page;
        int s = size == null || size <= 0 ? 10 : size;
        Page<QaInfo> pageResult = qaInfoMapper.selectPage(new Page<>(p, s),
                StringUtils.hasText(groupId)
                        ? new LambdaQueryWrapper<QaInfo>().eq(QaInfo::getGroupId, Long.valueOf(groupId))
                        : new LambdaQueryWrapper<QaInfo>().orderByDesc(QaInfo::getId));
        List<RagQAQueryVO> records = pageResult.getRecords().stream().map(info -> {
            List<QaElasticMap> maps = qaElasticMapMapper.selectList(new LambdaQueryWrapper<QaElasticMap>()
                    .eq(QaElasticMap::getGroupId, info.getGroupId()));
            Map<String, String> questionMap = new LinkedHashMap<>();
            maps.forEach(m -> questionMap.put(String.valueOf(m.getElasticId()), m.getQuestion()));
            return new RagQAQueryVO(String.valueOf(info.getGroupId()), questionMap, info.getAnswer());
        }).toList();
        return new PageResult<>((int) pageResult.getCurrent(), (int) pageResult.getSize(),
                pageResult.getTotal(), records);
    }

    private List<Float> toList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }
}
