package com.fancy.taxiagent.order.service;

import com.fancy.taxiagent.order.domain.entity.OrderRoute;
import com.fancy.taxiagent.order.domain.vo.EstRouteVO;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * MongoDB 订单轨迹存取（_id = mongoTraceId）。
 */
@Service
public class OrderRouteService {

    private final MongoTemplate mongoTemplate;

    public OrderRouteService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void addOrder(String traceId, EstRouteVO estRouteVO, Long ownerUserId) {
        OrderRoute orderRoute = new OrderRoute();
        orderRoute.setMongoTraceId(traceId);
        orderRoute.setOwnerUserId(ownerUserId);
        orderRoute.setEstRoute(estRouteVO.estRoute());
        orderRoute.setEstPolyline(estRouteVO.estPolyline());
        mongoTemplate.save(orderRoute);
    }

    public void updateRealPolyline(String traceId, String realPolyline) {
        Query query = Query.query(Criteria.where("_id").is(traceId));
        Update update = new Update();
        update.set("realPolyline", realPolyline);
        mongoTemplate.updateFirst(query, update, OrderRoute.class);
    }

    public OrderRoute getByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("TraceID cannot be blank");
        }
        return mongoTemplate.findById(traceId, OrderRoute.class);
    }
}
