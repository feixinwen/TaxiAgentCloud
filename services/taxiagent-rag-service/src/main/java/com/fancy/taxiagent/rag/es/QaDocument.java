package com.fancy.taxiagent.rag.es;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * ES 知识库文档：一条问题 = 一个文档（_id = elastic_id 字符串），同组问题共享 groupId 用于去重。
 */
@Document(indexName = "qa_knowledge_base")
public class QaDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String groupId;

    @Field(type = FieldType.Text, analyzer = "ik_smart", searchAnalyzer = "ik_smart")
    private String question;

    @Field(type = FieldType.Dense_Vector, dims = 1024, similarity = "cosine")
    private float[] questionVector;

    @Field(type = FieldType.Text, index = false)
    private String answer;

    public QaDocument() {
    }

    public QaDocument(String id, String groupId, String question, float[] questionVector, String answer) {
        this.id = id;
        this.groupId = groupId;
        this.question = question;
        this.questionVector = questionVector;
        this.answer = answer;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public float[] getQuestionVector() {
        return questionVector;
    }

    public void setQuestionVector(float[] questionVector) {
        this.questionVector = questionVector;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }
}
