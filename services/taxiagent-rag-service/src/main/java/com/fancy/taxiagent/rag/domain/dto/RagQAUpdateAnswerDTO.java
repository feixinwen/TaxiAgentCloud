package com.fancy.taxiagent.rag.domain.dto;

public class RagQAUpdateAnswerDTO {

    private String groupId;
    private String answer;

    public RagQAUpdateAnswerDTO() {
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }
}
