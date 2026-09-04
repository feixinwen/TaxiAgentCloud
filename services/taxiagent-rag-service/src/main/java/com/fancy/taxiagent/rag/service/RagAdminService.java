package com.fancy.taxiagent.rag.service;

import com.fancy.taxiagent.rag.domain.dto.RagQAQueryVO;
import com.fancy.taxiagent.rag.domain.vo.PageResult;

import java.util.List;

public interface RagAdminService {

    void addQA(List<String> questions, String answer);

    void addQAs(List<List<String>> questionGroups, List<String> answers);

    void deleteQA(List<String> groupIds, List<String> questionIds);

    void updateAnswer(String groupId, String answer);

    void updateQuestion(String questionId, String question);

    PageResult<RagQAQueryVO> queryPage(Integer page, Integer size, String groupId);
}
