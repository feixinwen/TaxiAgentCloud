package com.fancy.taxiagent.rag.domain.dto;

public class RagQAUpdateQuestionDTO {

    private String questionId;
    private String question;

    public RagQAUpdateQuestionDTO() {
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
