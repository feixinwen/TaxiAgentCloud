package com.fancy.taxiagent.rag.domain.dto;

public class RagSearchRequest {

    private String question;
    private Integer topK;

    public RagSearchRequest() {
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }
}
