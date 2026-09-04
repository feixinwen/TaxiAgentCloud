package com.fancy.taxiagent.rag.domain.dto;

import java.util.List;

public class RagQADelDTO {

    private List<String> groupIds;
    private List<String> questionIds;

    public RagQADelDTO() {
    }

    public List<String> getGroupIds() {
        return groupIds;
    }

    public void setGroupIds(List<String> groupIds) {
        this.groupIds = groupIds;
    }

    public List<String> getQuestionIds() {
        return questionIds;
    }

    public void setQuestionIds(List<String> questionIds) {
        this.questionIds = questionIds;
    }
}
