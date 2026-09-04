package com.fancy.taxiagent.order.controller;

import com.fancy.taxiagent.order.config.OrderSecurityConfiguration;
import com.fancy.taxiagent.order.config.OrderSecurityFailureHandler;
import com.fancy.taxiagent.order.domain.dto.Point;
import com.fancy.taxiagent.order.domain.vo.OrderEstimateVO;
import com.fancy.taxiagent.order.domain.vo.OrderRouteVO;
import com.fancy.taxiagent.order.domain.vo.PriceEstimateVO;
import com.fancy.taxiagent.order.domain.vo.RideOrderVO;
import com.fancy.taxiagent.order.exception.InvalidOrderRequestException;
import com.fancy.taxiagent.order.exception.OrderAccessDeniedException;
import com.fancy.taxiagent.order.exception.OrderNotFoundException;
import com.fancy.taxiagent.order.exception.OrderStateConflictException;
import com.fancy.taxiagent.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单接口的鉴权、userId/role 提取与错误映射验证（真实 JWT 安全链）。
 */
@WebMvcTest(controllers = OrderController.class)
@Import({OrderExceptionHandler.class, OrderSecurityConfiguration.class, OrderSecurityFailureHandler.class})
class OrderControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("90001", "USER"));
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("50000", "ADMIN"));
        when(jwtDecoder.decode("support-token")).thenReturn(jwt("50001", "SUPPORT"));
        when(jwtDecoder.decode("driver-token")).thenReturn(jwt("70001", "DRIVER"));
    }

    @Test
    void shouldAllowDriverAccept() throws Exception {
        when(orderService.driverAcceptOrder("1001", 70001L)).thenReturn(true);

        mockMvc.perform(post("/api/orders/driver/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer driver-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"1001\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("true"));
    }

    @Test
    void shouldRejectUserAccessingDriverEndpoint() throws Exception {
        mockMvc.perform(post("/api/orders/driver/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"1001\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldEstimatePrice() throws Exception {
        when(orderService.estimatePrice(eq(90001L), any(Point.class), any(Point.class), eq(1), eq(0)))
                .thenReturn(OrderEstimateVO.of("trace-1", new BigDecimal("5.23"),
                        new PriceEstimateVO(new BigDecimal("19.60"), new BigDecimal("1.00"),
                                new BigDecimal("14.60"), new BigDecimal("5.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO)));

        mockMvc.perform(post("/api/orders/estimate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startLat":31.2304,"startLng":121.4737,
                                 "endLat":31.3,"endLng":121.5,"vehicleType":1,"isExpedited":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estPrice").value(19.60))
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.estDistance").value(5.23));
    }

    @Test
    void shouldReturnRouteForOwner() throws Exception {
        when(orderService.getRouteByTraceId("trace-1", 90001L))
                .thenReturn(new OrderRouteVO("trace-1", "121.1,31.1;121.2,31.2", null));

        mockMvc.perform(get("/api/orders/routes/trace-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.estPolyline").value("121.1,31.1;121.2,31.2"))
                .andExpect(jsonPath("$.realPolyline").doesNotExist());
    }

    @Test
    void shouldReturn403ForRouteOfOtherUser() throws Exception {
        when(orderService.getRouteByTraceId("trace-other", 90001L))
                .thenThrow(new OrderAccessDeniedException("无权限查看该轨迹"));

        mockMvc.perform(get("/api/orders/routes/trace-other")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldCreateOrder() throws Exception {
        when(orderService.createOrder(any(), eq(90001L))).thenReturn("123456789");

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleType":1,"isExpedited":0,"isReservation":0,
                                 "startAddress":"起点","startLat":31.2304,"startLng":121.4737,
                                 "endAddress":"终点","endLat":31.3,"endLng":121.5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("123456789"));
    }

    @Test
    void shouldGetOrderDetail() throws Exception {
        when(orderService.getOrderDetail("1001", 90001L, "USER"))
                .thenReturn(vo());

        mockMvc.perform(get("/api/orders/1001").header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("1001"))
                .andExpect(jsonPath("$.estRoute").value("121.1,31.1;121.2,31.2"));
    }

    @Test
    void shouldReturn404ForMissingOrder() throws Exception {
        when(orderService.getOrderDetail("9999", 90001L, "USER"))
                .thenThrow(new OrderNotFoundException("订单不存在"));

        mockMvc.perform(get("/api/orders/9999").header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void shouldReturn409ForStateConflict() throws Exception {
        when(orderService.cancelOrder(eq("1001"), eq(90001L), eq("USER"), eq(1), any()))
                .thenThrow(new OrderStateConflictException("订单状态已变化"));

        mockMvc.perform(post("/api/orders/1001/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelRole\":1,\"cancelReason\":\"测试\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
    }

    @Test
    void shouldReturn400ForInvalidRequest() throws Exception {
        when(orderService.createOrder(any(), eq(90001L)))
                .thenThrow(new InvalidOrderRequestException("订单信息不完整"));

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldPageOrders() throws Exception {
        mockMvc.perform(post("/api/orders/page")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":1,\"size\":10,\"statusList\":[10,20]}"))
                .andExpect(status().isOk());

        verify(orderService).getOrderHistoryPage(eq(90001L), eq("USER"), eq(1), eq(10),
                eq(List.of(10, 20)));
    }

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/orders/1001")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn403ForNonUserRole() throws Exception {
        mockMvc.perform(get("/api/orders/1001").header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowAdminToCancelAsSystem() throws Exception {
        when(orderService.cancelOrder(eq("1001"), eq(50000L), eq("ADMIN"), eq(3), any()))
                .thenReturn("0.00");

        mockMvc.perform(post("/api/orders/1001/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelRole\":3,\"cancelReason\":\"客服取消\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("0.00"));
    }

    private RideOrderVO vo() {
        return new RideOrderVO("1001", "90001", null, "121.1,31.1;121.2,31.2", null,
                1, 0, 0, "1234", 10, null, null,
                LocalDateTime.now(), null, null, null, null, null, null,
                "起点", new BigDecimal("31.2304"), new BigDecimal("121.4737"),
                "终点", new BigDecimal("31.3"), new BigDecimal("121.5"),
                new BigDecimal("5.23"), null, new BigDecimal("19.60"), null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                LocalDateTime.now(), 0);
    }

    private Jwt jwt(String subject, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", role)
                .claim("token_type", "access")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }
}
