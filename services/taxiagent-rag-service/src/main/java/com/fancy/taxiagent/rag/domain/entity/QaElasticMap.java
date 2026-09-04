package com.fancy.taxiagent.rag.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sys_qa_es")
public class QaElasticMap {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long elasticId;
    private Long groupId;
    private String question;

    public QaElasticMap() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getElasticId() {
        return elasticId;
    }

    public void setElasticId(Long elasticId) {
        this.elasticId = elasticId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
