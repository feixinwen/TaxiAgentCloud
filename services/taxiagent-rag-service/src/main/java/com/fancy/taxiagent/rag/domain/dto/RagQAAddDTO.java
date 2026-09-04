package com.fancy.taxiagent.rag.domain.dto;

import java.util.List;

public class RagQAAddDTO {

    private List<String> questions;
    private String answer;

    public RagQAAddDTO() {
    }

    public List<String> getQuestions() {
        return questions;
    }

    public void setQuestions(List<String> questions) {
        this.questions = questions;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }
}
