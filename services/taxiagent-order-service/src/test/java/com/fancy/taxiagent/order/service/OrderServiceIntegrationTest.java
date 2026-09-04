package com.fancy.taxiagent.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.order.amap.service.AmapGeoRegeoService;
import com.fancy.taxiagent.order.amap.service.AmapRouteService;
import com.fancy.taxiagent.order.domain.dto.CreateOrderDTO;
import com.fancy.taxiagent.order.domain.entity.OrderRoute;
import com.fancy.taxiagent.order.domain.entity.RideOrder;
import com.fancy.taxiagent.order.domain.enums.RideOrderStatus;
import com.fancy.taxiagent.order.domain.vo.EstRouteVO;
import com.fancy.taxiagent.order.domain.vo.OrderBillVO;
import com.fancy.taxiagent.order.domain.vo.PageResult;
import com.fancy.taxiagent.order.domain.vo.RideOrderVO;
import com.fancy.taxiagent.order.exception.InvalidOrderRequestException;
import com.fancy.taxiagent.order.exception.OrderAccessDeniedException;
import com.fancy.taxiagent.order.exception.OrderNotFoundException;
import com.fancy.taxiagent.order.exception.OrderStateConflictException;
import com.fancy.taxiagent.order.mapper.RideOrderMapper;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/**
 * 订单核心链路集成测试（真实 MySQL/Mongo/Redis 容器 + Flyway + Amap mock）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false"
        }
)
class OrderServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_order");

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private OrderService orderService;

    @Autowired
    private RideOrderMapper rideOrderMapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private AmapRouteService amapRouteService;

    @MockitoBean
    private AmapGeoRegeoService amapGeoRegeoService;

    @BeforeEach
    void cleanDatabase() {
        rideOrderMapper.delete(null);
        mongoTemplate.dropCollection(OrderRoute.class);
    }

    @Test
    void contextLoadsAndFlywayApplied() {
        assertThat(redisTemplate.getConnectionFactory()).isNotNull();
        assertThat(mongoTemplate.getCollectionNames()).isNotNull();
        List<Object> orders = rideOrderMapper.selectObjs(
                new LambdaQueryWrapper<RideOrder>().select(RideOrder::getOrderId));
        assertThat(orders).isEmpty();
    }

    @Test
    void shouldCreateOrderWithAutomaticRoutePlanning() {
        mockRoutePlanning();

        String orderId = orderService.createOrder(createDto(null, null), 90001L);

        RideOrder order = findByOrderId(orderId);
        assertThat(order).isNotNull();
        assertThat(order.getUserId()).isEqualTo(90001L);
        assertThat(order.getOrderStatus()).isEqualTo(RideOrderStatus.CREATED.getCode());
        assertThat(order.getMongoTraceId()).isNotBlank();
        assertThat(order.getEstPrice()).isNotNull();
        assertThat(order.getEstDistance()).isEqualByComparingTo("5.23");
        assertThat(order.getSafetyCode()).matches("\\d{4}");
        assertThat(mongoTemplate.findById(order.getMongoTraceId(), OrderRoute.class))
                .isNotNull();
    }

    @Test
    void shouldRejectIncompleteOrder() {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setStartLat(new BigDecimal("31.23"));
        dto.setStartLng(new BigDecimal("121.47"));

        assertThatThrownBy(() -> orderService.createOrder(dto, 90001L))
                .isInstanceOf(InvalidOrderRequestException.class);
    }

    @Test
    void shouldKeepAddressEmptyWhenRegeoFails() {
        mockRoutePlanning();
        when(amapGeoRegeoService.getRegeo(anyDouble(), anyDouble())).thenReturn(Optional.empty());

        CreateOrderDTO dto = createDto("121.5", "31.3");
        dto.setStartAddress(null);
        dto.setEndAddress(null);

        String orderId = orderService.createOrder(dto, 90001L);

        RideOrder order = findByOrderId(orderId);
        assertThat(order.getStartAddress()).isNull();
        assertThat(order.getEndAddress()).isNull();
    }

    @Test
    void shouldGetOwnOrderAndHideOthersOrder() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);

        RideOrderVO own = orderService.getOrderDetail(orderId, 90001L, "USER");
        assertThat(own.orderId()).isEqualTo(orderId);
        assertThat(own.estRoute()).isEqualTo("121.1,31.1;121.2,31.2");

        assertThatThrownBy(() -> orderService.getOrderDetail(orderId, 90002L, "USER"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void shouldAllowAdminToViewAnyOrder() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);

        RideOrderVO vo = orderService.getOrderDetail(orderId, 50000L, "ADMIN");
        assertThat(vo.orderId()).isEqualTo(orderId);
    }

    @Test
    void shouldPageOwnOrdersByRoleAndStatus() {
        mockRoutePlanning();
        orderService.createOrder(createDto("121.1", "31.1"), 90001L);
        orderService.createOrder(createDto("121.2", "31.2"), 90001L);
        orderService.createOrder(createDto("121.3", "31.3"), 90002L);

        PageResult<RideOrderVO> page = orderService.getOrderHistoryPage(90001L, "USER", 1, 10, null);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).hasSize(2);

        PageResult<RideOrderVO> admin = orderService.getOrderHistoryPage(90001L, "ADMIN", 1, 10, null);
        assertThat(admin.total()).isEqualTo(3);

        PageResult<RideOrderVO> cancelled = orderService.getOrderHistoryPage(
                90001L, "USER", 1, 10, List.of(RideOrderStatus.CANCELLED.getCode()));
        assertThat(cancelled.total()).isZero();
    }

    @Test
    void shouldListOngoingOrderIdsExcludingPaidAndCancelled() {
        mockRoutePlanning();
        orderService.createOrder(createDto("121.1", "31.1"), 90001L);

        List<String> ongoing = orderService.getUserOngoingOrderIds(90001L);
        assertThat(ongoing).hasSize(1);
    }

    @Test
    void shouldPayOwnOrderInFinishedState() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);
        RideOrder order = findByOrderId(orderId);
        order.setOrderStatus(RideOrderStatus.FINISHED_WAIT_PAY.getCode());
        rideOrderMapper.updateById(order);

        Boolean paid = orderService.payOrder(orderId, 90001L, 1, "trade-1");

        assertThat(paid).isTrue();
        assertThat(rideOrderMapper.selectById(order.getId()).getOrderStatus())
                .isEqualTo(RideOrderStatus.PAID.getCode());
    }

    @Test
    void shouldRejectPayOfOthersOrder() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);

        assertThatThrownBy(() -> orderService.payOrder(orderId, 90002L, 1, "trade-2"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void shouldCancelOwnCreatedOrderWithoutFee() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);

        String fee = orderService.cancelOrder(orderId, 90001L, "USER", 1, "不想去了");

        assertThat(new BigDecimal(fee)).isEqualByComparingTo("0.00");
        RideOrder order = findByOrderId(orderId);
        assertThat(order.getOrderStatus()).isEqualTo(RideOrderStatus.CANCELLED.getCode());
        assertThat(order.getCancelReason()).isEqualTo("不想去了");
        assertThat(order.getPriceBase()).isEqualByComparingTo("0.00");
    }

    @Test
    void shouldChargeCancelFeeWhenDriverAccepted() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);
        RideOrder order = findByOrderId(orderId);
        order.setOrderStatus(RideOrderStatus.DRIVER_ACCEPTED.getCode());
        order.setDriverId(70001L);
        // 1 小时前接单 → 违约金 5 + 55*0.5 = 32.50 → 封顶 20.00
        order.setDriverAcceptTime(LocalDateTime.now().minusHours(1));
        rideOrderMapper.updateById(order);

        String fee = orderService.cancelOrder(orderId, 90001L, "USER", 1, "等太久");

        assertThat(new BigDecimal(fee)).isEqualByComparingTo("20.00");
    }

    @Test
    void shouldRejectCancelAfterTripStarted() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);
        RideOrder order = findByOrderId(orderId);
        order.setOrderStatus(RideOrderStatus.IN_TRIP.getCode());
        rideOrderMapper.updateById(order);

        assertThatThrownBy(() -> orderService.cancelOrder(orderId, 90001L, "USER", 1, "测试"))
                .isInstanceOf(InvalidOrderRequestException.class);
    }

    @Test
    void shouldRejectDriverCancelRoleThisPhase() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);

        assertThatThrownBy(() -> orderService.cancelOrder(orderId, 90001L, "USER", 2, "司机取消"))
                .isInstanceOf(InvalidOrderRequestException.class);
    }

    @Test
    void shouldAllowAdminSystemCancel() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);

        String fee = orderService.cancelOrder(orderId, 50000L, "ADMIN", 3, "客服取消");

        assertThat(new BigDecimal(fee)).isEqualByComparingTo("0.00");
        assertThat(findByOrderId(orderId).getOrderStatus()).isEqualTo(RideOrderStatus.CANCELLED.getCode());
    }

    // ============ 司机端 ============

    @Test
    void shouldRejectAcceptWhenDriverHasOngoingOrder() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);
        assertThat(orderService.driverAcceptOrder(orderId, 70001L)).isTrue();

        String second = orderService.createOrder(createDto("121.2", "31.2"), 90001L);
        assertThatThrownBy(() -> orderService.driverAcceptOrder(second, 70001L))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void shouldAcceptOrderAndRejectSecondDriver() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);

        assertThat(orderService.driverAcceptOrder(orderId, 70001L)).isTrue();
        assertThat(orderService.driverAcceptOrder(orderId, 70002L)).isFalse();

        RideOrder order = findByOrderId(orderId);
        assertThat(order.getDriverId()).isEqualTo(70001L);
        assertThat(order.getOrderStatus()).isEqualTo(RideOrderStatus.DRIVER_ACCEPTED.getCode());
        assertThat(order.getDriverAcceptTime()).isNotNull();
    }

    @Test
    void shouldCompleteDriverTripFlowWithBill() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);

        assertThat(orderService.driverAcceptOrder(orderId, 70001L)).isTrue();
        assertThat(orderService.driverArriveStart(orderId, 70001L)).isTrue();
        assertThat(orderService.startRide(orderId, 70001L)).isTrue();
        RideOrder inTrip = findByOrderId(orderId);
        assertThat(inTrip.getOrderStatus()).isEqualTo(RideOrderStatus.IN_TRIP.getCode());
        assertThat(inTrip.getPickupTime()).isNotNull();

        // 同点 polyline → 真实里程 0.00；arriveTime 显式传入（不再依赖截断精度断言具体金额）
        OrderBillVO bill = orderService.finishRide(orderId, 70001L,
                new BigDecimal("31.3"), new BigDecimal("121.5"), "终点",
                "121.4737,31.2304;121.4737,31.2304",
                inTrip.getPickupTime().plusMinutes(10));

        assertThat(bill.orderId()).isEqualTo(orderId);
        assertThat(bill.priceBase()).isEqualByComparingTo("8.00");
        assertThat(bill.priceDistance()).isEqualByComparingTo("0.00");
        assertThat(bill.priceRadio()).isEqualByComparingTo("1.00");
        assertThat(bill.realPrice()).isGreaterThan(new BigDecimal("8.00"));

        RideOrder finished = findByOrderId(orderId);
        assertThat(finished.getOrderStatus()).isEqualTo(RideOrderStatus.FINISHED_WAIT_PAY.getCode());
        assertThat(finished.getRealDistance()).isEqualByComparingTo("0.00");
        assertThat(finished.getFinishTime()).isNotNull();
        assertThat(finished.getEndAddress()).isEqualTo("终点");
        // Mongo 真实轨迹已写入
        OrderRoute route = mongoTemplate.findById(finished.getMongoTraceId(), OrderRoute.class);
        assertThat(route.getRealPolyline()).isEqualTo("121.4737,31.2304;121.4737,31.2304");
    }

    @Test
    void shouldRejectFinishByNonHandler() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);
        orderService.driverAcceptOrder(orderId, 70001L);
        orderService.driverArriveStart(orderId, 70001L);
        orderService.startRide(orderId, 70001L);

        assertThatThrownBy(() -> orderService.finishRide(orderId, 70002L, null, null, null,
                "121.4737,31.2304;121.4737,31.2304", LocalDateTime.now()))
                .isInstanceOf(OrderAccessDeniedException.class);
    }

    @Test
    void shouldRejectFinishBeforeTrip() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);
        orderService.driverAcceptOrder(orderId, 70001L);

        assertThatThrownBy(() -> orderService.finishRide(orderId, 70001L, null, null, null,
                "121.4737,31.2304;121.4737,31.2304", LocalDateTime.now()))
                .isInstanceOf(InvalidOrderRequestException.class);
    }

    @Test
    void shouldListDriverTasksAndPool() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);
        orderService.driverAcceptOrder(orderId, 70001L);

        // 待处理任务含已接单
        assertThat(orderService.getDriverOrderTasks(70001L, false)).hasSize(1);
        assertThat(orderService.getDriverOrderTasks(70001L, true)).isEmpty();
        // 工单池只含未接单
        String poolOrder = orderService.createOrder(createDto("121.3", "31.3"), 90002L);
        PageResult<RideOrderVO> pool = orderService.getDriverOrderPool(1, 10);
        assertThat(pool.total()).isEqualTo(1);
        assertThat(pool.records().get(0).orderId()).isEqualTo(poolOrder);
    }

    @Test
    void shouldGetDriverCurrentOrderWithRoute() {
        mockRoutePlanning();
        String orderId = orderService.createOrder(createDto("121.1", "31.1"), 90001L);
        orderService.driverAcceptOrder(orderId, 70001L);

        RideOrderVO current = orderService.getDriverCurrentOrder(70001L);
        assertThat(current.orderId()).isEqualTo(orderId);
        assertThat(current.estRoute()).isEqualTo("121.1,31.1;121.2,31.2");

        assertThat(orderService.getDriverCurrentOrder(70002L)).isNull();
    }

    private void mockRoutePlanning() {
        when(amapRouteService.getDrivingRouteEst(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new EstRouteVO("{\"paths\":[...]}", "121.1,31.1;121.2,31.2", "5.234", "952"));
        when(amapGeoRegeoService.getRegeo(anyDouble(), anyDouble()))
                .thenReturn(Optional.empty());
    }

    private RideOrder findByOrderId(String orderId) {
        return rideOrderMapper.selectOne(
                new LambdaQueryWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, Long.parseLong(orderId)));
    }

    private CreateOrderDTO createDto(String endLng, String endLat) {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setVehicleType(1);
        dto.setIsExpedited(0);
        dto.setIsReservation(0);
        dto.setStartAddress("起点");
        dto.setStartLat(new BigDecimal("31.2304"));
        dto.setStartLng(new BigDecimal("121.4737"));
        dto.setEndAddress("终点");
        if (endLng != null) {
            dto.setEndLat(new BigDecimal(endLat));
            dto.setEndLng(new BigDecimal(endLng));
        } else {
            dto.setEndLat(new BigDecimal("31.3"));
            dto.setEndLng(new BigDecimal("121.5"));
        }
        return dto;
    }
}
