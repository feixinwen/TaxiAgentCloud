package com.fancy.taxiagent.rag.domain.dto;

public class RagQAQueryDTO {

    private Integer page;
    private Integer size;
    private String groupId;

    public RagQAQueryDTO() {
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
}
