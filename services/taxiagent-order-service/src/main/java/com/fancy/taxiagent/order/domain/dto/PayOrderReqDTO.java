package com.fancy.taxiagent.order.domain.dto;

public class PayOrderReqDTO {

    private Integer payChannel;
    private String tradeNo;

    public PayOrderReqDTO() {
    }

    public Integer getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(Integer payChannel) {
        this.payChannel = payChannel;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }
}
