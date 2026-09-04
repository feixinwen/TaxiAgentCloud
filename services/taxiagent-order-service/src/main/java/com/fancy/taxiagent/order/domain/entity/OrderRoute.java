package com.fancy.taxiagent.order.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "order_routes")
public class OrderRoute {

    @Id
    private String mongoTraceId;
    private Long ownerUserId;
    private String estRoute;
    private String estPolyline;
    private String realPolyline;

    public OrderRoute() {
    }

    public String getMongoTraceId() {
        return mongoTraceId;
    }

    public void setMongoTraceId(String mongoTraceId) {
        this.mongoTraceId = mongoTraceId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getEstRoute() {
        return estRoute;
    }

    public void setEstRoute(String estRoute) {
        this.estRoute = estRoute;
    }

    public String getEstPolyline() {
        return estPolyline;
    }

    public void setEstPolyline(String estPolyline) {
        this.estPolyline = estPolyline;
    }

    public String getRealPolyline() {
        return realPolyline;
    }

    public void setRealPolyline(String realPolyline) {
        this.realPolyline = realPolyline;
    }
}
