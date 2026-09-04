package com.fancy.taxiagent.order.domain.dto;

import java.util.List;

public class OrderPageReqDTO {

    private Integer page;
    private Integer size;
    private List<Integer> statusList;

    public OrderPageReqDTO() {
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

    public List<Integer> getStatusList() {
        return statusList;
    }

    public void setStatusList(List<Integer> statusList) {
        this.statusList = statusList;
    }
}
