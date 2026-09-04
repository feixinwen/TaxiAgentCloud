package com.fancy.taxiagent.order.controller;

import com.fancy.taxiagent.order.domain.dto.CancelOrderReqDTO;
import com.fancy.taxiagent.order.domain.dto.CreateOrderDTO;
import com.fancy.taxiagent.order.domain.dto.DriverAcceptReqDTO;
import com.fancy.taxiagent.order.domain.dto.DriverActionReqDTO;
import com.fancy.taxiagent.order.domain.dto.FinishRideReqDTO;
import com.fancy.taxiagent.order.domain.dto.OrderPageReqDTO;
import com.fancy.taxiagent.order.domain.dto.PayOrderReqDTO;
import com.fancy.taxiagent.order.domain.dto.Point;
import com.fancy.taxiagent.order.domain.dto.PriceEstimateReqDTO;
import com.fancy.taxiagent.order.domain.vo.OrderBillVO;
import com.fancy.taxiagent.order.domain.vo.OrderEstimateVO;
import com.fancy.taxiagent.order.domain.vo.OrderRouteVO;
import com.fancy.taxiagent.order.domain.vo.PageResult;
import com.fancy.taxiagent.order.domain.vo.RideOrderVO;
import com.fancy.taxiagent.order.service.OrderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 乘客端订单接口。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/estimate")
    @PreAuthorize("hasRole('USER')")
    public OrderEstimateVO estimate(@RequestBody PriceEstimateReqDTO req, Authentication authentication) {
        return orderService.estimatePrice(
                userId(authentication),
                new Point(req.getStartLat(), req.getStartLng()),
                new Point(req.getEndLat(), req.getEndLng()),
                req.getVehicleType(), req.getIsExpedited());
    }

    @GetMapping("/routes/{traceId}")
    @PreAuthorize("hasRole('USER')")
    public OrderRouteVO route(@PathVariable String traceId, Authentication authentication) {
        return orderService.getRouteByTraceId(traceId, userId(authentication));
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public String create(@RequestBody CreateOrderDTO req, Authentication authentication) {
        return orderService.createOrder(req, userId(authentication));
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('USER')")
    public RideOrderVO detail(@PathVariable String orderId, Authentication authentication) {
        return orderService.getOrderDetail(orderId, userId(authentication), role(authentication));
    }

    @PostMapping("/page")
    @PreAuthorize("hasAnyRole('USER','ADMIN','SUPPORT')")
    public PageResult<RideOrderVO> page(@RequestBody OrderPageReqDTO req, Authentication authentication) {
        return orderService.getOrderHistoryPage(
                userId(authentication), role(authentication),
                req.getPage(), req.getSize(), req.getStatusList());
    }

    @GetMapping("/my/ongoing")
    @PreAuthorize("hasRole('USER')")
    public List<String> ongoingOrderIds(Authentication authentication) {
        return orderService.getUserOngoingOrderIds(userId(authentication));
    }

    @PostMapping("/{orderId}/pay")
    @PreAuthorize("hasRole('USER')")
    public Boolean pay(@PathVariable String orderId, @RequestBody PayOrderReqDTO req,
                       Authentication authentication) {
        return orderService.payOrder(orderId, userId(authentication), req.getPayChannel(), req.getTradeNo());
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasAnyRole('USER','ADMIN','SUPPORT')")
    public String cancel(@PathVariable String orderId, @RequestBody CancelOrderReqDTO req,
                         Authentication authentication) {
        return orderService.cancelOrder(orderId, userId(authentication), role(authentication),
                req.getCancelRole(), req.getCancelReason());
    }

    // ============ 司机端 ============

    @PostMapping("/driver/accept")
    @PreAuthorize("hasRole('DRIVER')")
    public Boolean driverAccept(@RequestBody DriverAcceptReqDTO req, Authentication authentication) {
        return orderService.driverAcceptOrder(req.getOrderId(), userId(authentication));
    }

    @PostMapping("/driver/arrive")
    @PreAuthorize("hasRole('DRIVER')")
    public Boolean driverArrive(@RequestBody DriverActionReqDTO req, Authentication authentication) {
        return orderService.driverArriveStart(req.getOrderId(), userId(authentication));
    }

    @PostMapping("/driver/start")
    @PreAuthorize("hasRole('DRIVER')")
    public Boolean driverStart(@RequestBody DriverActionReqDTO req, Authentication authentication) {
        return orderService.startRide(req.getOrderId(), userId(authentication));
    }

    @PostMapping("/driver/finish")
    @PreAuthorize("hasRole('DRIVER')")
    public OrderBillVO driverFinish(@RequestBody FinishRideReqDTO req, Authentication authentication) {
        return orderService.finishRide(req.getOrderId(), userId(authentication),
                req.getEndLat(), req.getEndLng(), req.getEndAddress(),
                req.getRealPolyline(), req.getArriveTime());
    }

    @GetMapping("/driver/tasks")
    @PreAuthorize("hasRole('DRIVER')")
    public List<RideOrderVO> driverTasks(@RequestParam Boolean isFinished, Authentication authentication) {
        return orderService.getDriverOrderTasks(userId(authentication), isFinished);
    }

    @GetMapping("/driver/pool/page")
    @PreAuthorize("hasRole('DRIVER')")
    public PageResult<RideOrderVO> driverPool(@RequestParam Integer page, @RequestParam Integer size) {
        return orderService.getDriverOrderPool(page, size);
    }

    @GetMapping("/driver/current")
    @PreAuthorize("hasRole('DRIVER')")
    public RideOrderVO driverCurrent(Authentication authentication) {
        return orderService.getDriverCurrentOrder(userId(authentication));
    }

    private Long userId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }

    private String role(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .findFirst()
                .map(authority -> authority.substring("ROLE_".length()).toUpperCase(Locale.ROOT))
                .orElse("USER");
    }
}
