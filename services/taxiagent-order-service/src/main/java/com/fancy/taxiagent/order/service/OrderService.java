package com.fancy.taxiagent.order.service;

import com.fancy.taxiagent.order.domain.dto.CreateOrderDTO;
import com.fancy.taxiagent.order.domain.dto.Point;
import com.fancy.taxiagent.order.domain.vo.OrderBillVO;
import com.fancy.taxiagent.order.domain.vo.OrderEstimateVO;
import com.fancy.taxiagent.order.domain.vo.OrderRouteVO;
import com.fancy.taxiagent.order.domain.vo.PageResult;
import com.fancy.taxiagent.order.domain.vo.RideOrderVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderService {

    OrderEstimateVO estimatePrice(Long userId, Point start, Point end, Integer vehicleType, Integer isExpedited);

    String createOrder(CreateOrderDTO dto, Long userId);

    OrderRouteVO getRouteByTraceId(String traceId, Long userId);

    RideOrderVO getOrderDetail(String orderId, Long operatorId, String operatorRole);

    PageResult<RideOrderVO> getOrderHistoryPage(Long userId, String role, Integer page, Integer size,
                                                List<Integer> statusList);

    List<String> getUserOngoingOrderIds(Long userId);

    Boolean payOrder(String orderId, Long operatorId, Integer payChannel, String tradeNo);

    String cancelOrder(String orderId, Long operatorId, String operatorRole,
                       Integer cancelRole, String cancelReason);

    // ============ 司机端 ============

    Boolean driverAcceptOrder(String orderId, Long driverId);

    Boolean driverArriveStart(String orderId, Long driverId);

    Boolean startRide(String orderId, Long driverId);

    OrderBillVO finishRide(String orderId, Long driverId, BigDecimal endLat, BigDecimal endLng,
                           String endAddress, String realPolyline, LocalDateTime arriveTime);

    List<RideOrderVO> getDriverOrderTasks(Long driverId, Boolean isFinished);

    PageResult<RideOrderVO> getDriverOrderPool(Integer page, Integer size);

    RideOrderVO getDriverCurrentOrder(Long driverId);
}
