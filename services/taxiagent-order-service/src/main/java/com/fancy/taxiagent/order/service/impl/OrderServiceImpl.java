package com.fancy.taxiagent.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fancy.taxiagent.order.amap.pojo.georegeo.Regeocode;
import com.fancy.taxiagent.order.amap.service.AmapGeoRegeoService;
import com.fancy.taxiagent.order.amap.service.AmapRouteService;
import com.fancy.taxiagent.order.domain.dto.CreateOrderDTO;
import com.fancy.taxiagent.order.domain.dto.Point;
import com.fancy.taxiagent.order.domain.entity.OrderRoute;
import com.fancy.taxiagent.order.domain.entity.RideOrder;
import com.fancy.taxiagent.order.domain.enums.RideOrderStatus;
import com.fancy.taxiagent.order.domain.vo.EstRouteVO;
import com.fancy.taxiagent.order.domain.vo.OrderBillVO;
import com.fancy.taxiagent.order.domain.vo.OrderEstimateVO;
import com.fancy.taxiagent.order.domain.vo.OrderRouteVO;
import com.fancy.taxiagent.order.domain.vo.PageResult;
import com.fancy.taxiagent.order.domain.vo.PriceEstimateVO;
import com.fancy.taxiagent.order.domain.vo.RideOrderVO;
import com.fancy.taxiagent.order.exception.InvalidOrderRequestException;
import com.fancy.taxiagent.order.exception.OrderAccessDeniedException;
import com.fancy.taxiagent.order.exception.OrderNotFoundException;
import com.fancy.taxiagent.order.exception.OrderStateConflictException;
import com.fancy.taxiagent.order.id.IdGenerator;
import com.fancy.taxiagent.order.mapper.RideOrderMapper;
import com.fancy.taxiagent.order.price.PriceCalculator;
import com.fancy.taxiagent.order.service.OrderRouteService;
import com.fancy.taxiagent.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单核心服务。业务规则与单体 RideOrderServiceImpl 一致；
 * 差异：身份来自调用方参数（非 ThreadLocal），越权返回 404 不泄露存在性，逆地理失败地址留空。
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final RideOrderMapper rideOrderMapper;
    private final AmapRouteService amapRouteService;
    private final AmapGeoRegeoService amapGeoRegeoService;
    private final OrderRouteService orderRouteService;
    private final PriceCalculator priceCalculator;
    private final IdGenerator idGenerator;

    public OrderServiceImpl(RideOrderMapper rideOrderMapper,
                            AmapRouteService amapRouteService,
                            AmapGeoRegeoService amapGeoRegeoService,
                            OrderRouteService orderRouteService,
                            PriceCalculator priceCalculator,
                            IdGenerator idGenerator) {
        this.rideOrderMapper = rideOrderMapper;
        this.amapRouteService = amapRouteService;
        this.amapGeoRegeoService = amapGeoRegeoService;
        this.orderRouteService = orderRouteService;
        this.priceCalculator = priceCalculator;
        this.idGenerator = idGenerator;
    }

    @Override
    public OrderEstimateVO estimatePrice(Long userId, Point start, Point end, Integer vehicleType, Integer isExpedited) {
        if (start == null || end == null
                || start.lat() == null || start.lng() == null
                || end.lat() == null || end.lng() == null) {
            throw new InvalidOrderRequestException("起终点经纬度不能为空");
        }
        EstRouteVO estRoute = amapRouteService.getDrivingRouteEst(
                start.lng().doubleValue(), start.lat().doubleValue(),
                end.lng().doubleValue(), end.lat().doubleValue());
        if (estRoute == null || estRoute.estKm() == null || estRoute.estTime() == null) {
            throw new OrderStateConflictException("路径规划失败：返回里程/时间为空");
        }
        // 估算阶段即落轨迹，下单透传 traceId 后无需二次算路（见 createOrder 的补全分支）
        String traceId = UUID.randomUUID().toString();
        orderRouteService.addOrder(traceId, estRoute, userId);
        PriceEstimateVO price = priceCalculator.calculate(
                estRoute.estKm(), estRoute.estTime(), vehicleType, isExpedited);
        return OrderEstimateVO.of(traceId, new BigDecimal(estRoute.estKm()), price);
    }

    @Override
    public String createOrder(CreateOrderDTO dto, Long userId) {
        if (dto == null) {
            throw new InvalidOrderRequestException("订单参数不能为空");
        }
        if (dto.getVehicleType() == null) {
            dto.setVehicleType(1);
        }
        if (dto.getIsExpedited() == null) {
            dto.setIsExpedited(0);
        }
        if (dto.getIsReservation() == null) {
            dto.setIsReservation(0);
        }

        List<String> missing = new ArrayList<>();
        if (dto.getStartLat() == null || dto.getStartLng() == null) {
            missing.add("startLat/startLng");
        }
        if (dto.getEndLat() == null || dto.getEndLng() == null) {
            missing.add("endLat/endLng");
        }
        if (dto.getIsReservation() == 1 && dto.getScheduledTime() == null) {
            missing.add("scheduledTime");
        }
        if (!missing.isEmpty()) {
            throw new InvalidOrderRequestException("订单信息不完整，缺失: " + String.join(", ", missing));
        }

        supplementAddress(dto);

        // 计价/轨迹自动补全（对齐单体 needRoutePlan：任一缺失则规划）
        if (dto.getMongoTraceId() == null
                || dto.getEstDistance() == null
                || dto.getEstPrice() == null
                || dto.getRadio() == null) {
            EstRouteVO estRoute = amapRouteService.getDrivingRouteEst(
                    dto.getStartLng().doubleValue(), dto.getStartLat().doubleValue(),
                    dto.getEndLng().doubleValue(), dto.getEndLat().doubleValue());
            if (estRoute == null || estRoute.estKm() == null || estRoute.estTime() == null) {
                throw new OrderStateConflictException("路径规划失败：返回里程/时间为空");
            }
            String traceId = normalizeText(dto.getMongoTraceId());
            if (traceId == null) {
                traceId = UUID.randomUUID().toString();
            }
            orderRouteService.addOrder(traceId, estRoute, userId);
            dto.setMongoTraceId(traceId);
            dto.setEstDistance(new BigDecimal(estRoute.estKm()));
            PriceEstimateVO price = priceCalculator.calculate(
                    estRoute.estKm(), estRoute.estTime(), dto.getVehicleType(), dto.getIsExpedited());
            dto.setEstPrice(price.estPrice());
            dto.setRadio(price.estPriceRadio());
        }

        Long orderId = idGenerator.nextId();
        RideOrder order = new RideOrder();
        order.setOrderId(orderId);
        order.setUserId(userId);
        order.setMongoTraceId(normalizeText(dto.getMongoTraceId()));
        order.setVehicleType(dto.getVehicleType());
        order.setIsReservation(dto.getIsReservation());
        order.setIsExpedited(dto.getIsExpedited());
        order.setSafetyCode(generateSafetyCode());
        order.setOrderStatus(RideOrderStatus.CREATED.getCode());
        order.setCreateTime(LocalDateTime.now());
        order.setScheduledTime(dto.getScheduledTime());
        order.setStartAddress(normalizeText(dto.getStartAddress()));
        order.setStartLat(dto.getStartLat());
        order.setStartLng(dto.getStartLng());
        order.setEndAddress(normalizeText(dto.getEndAddress()));
        order.setEndLat(dto.getEndLat());
        order.setEndLng(dto.getEndLng());
        order.setEstDistance(dto.getEstDistance());
        order.setEstPrice(dto.getEstPrice());
        order.setPriceBase(BigDecimal.ZERO);
        order.setPriceTime(BigDecimal.ZERO);
        order.setPriceDistance(BigDecimal.ZERO);
        order.setPriceExpedited(BigDecimal.ZERO);
        order.setPriceRadio(dto.getRadio() == null ? BigDecimal.ZERO : dto.getRadio());
        order.setUpdateTime(LocalDateTime.now());
        order.setIsDeleted(0);
        rideOrderMapper.insert(order);
        return order.getOrderId().toString();
    }

    @Override
    public OrderRouteVO getRouteByTraceId(String traceId, Long userId) {
        if (traceId == null || traceId.isBlank() || userId == null) {
            throw new InvalidOrderRequestException("traceId 不能为空");
        }
        OrderRoute route = orderRouteService.getByTraceId(traceId);
        if (route == null) {
            throw new OrderNotFoundException("轨迹不存在");
        }
        if (!Objects.equals(route.getOwnerUserId(), userId)) {
            throw new OrderAccessDeniedException("无权限查看该轨迹");
        }
        return new OrderRouteVO(route.getMongoTraceId(), route.getEstPolyline(), route.getRealPolyline());
    }

    @Override
    public RideOrderVO getOrderDetail(String orderId, Long operatorId, String operatorRole) {
        RideOrder order = getRequiredOrder(orderId);
        assertCanViewOrder(order, operatorId, operatorRole);
        OrderRoute route = orderRouteService.getByTraceId(order.getMongoTraceId());
        return toDetailVO(order, route);
    }

    @Override
    public PageResult<RideOrderVO> getOrderHistoryPage(Long userId, String role, Integer page, Integer size,
                                                       List<Integer> statusList) {
        int p = page == null ? 1 : page;
        int s = size == null ? 10 : size;
        if (p <= 0 || s <= 0) {
            throw new InvalidOrderRequestException("page/size必须为正数");
        }
        LambdaQueryWrapper<RideOrder> qw = new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getIsDeleted, 0);
        if (statusList != null && !statusList.isEmpty()) {
            qw.in(RideOrder::getOrderStatus, statusList);
        }
        if (!isAdminOrSupport(role)) {
            qw.eq(RideOrder::getUserId, userId);
        }
        return queryOrderPage(qw, p, s);
    }

    @Override
    public List<String> getUserOngoingOrderIds(Long userId) {
        LambdaQueryWrapper<RideOrder> qw = new LambdaQueryWrapper<RideOrder>()
                .select(RideOrder::getOrderId)
                .eq(RideOrder::getUserId, userId)
                .eq(RideOrder::getIsDeleted, 0)
                .notIn(RideOrder::getOrderStatus, List.of(
                        RideOrderStatus.PAID.getCode(),
                        RideOrderStatus.CANCELLED.getCode()))
                .orderByDesc(RideOrder::getCreateTime);
        return rideOrderMapper.selectList(qw).stream()
                .map(RideOrder::getOrderId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
    }

    @Override
    public Boolean payOrder(String orderId, Long operatorId, Integer payChannel, String tradeNo) {
        RideOrder order = getRequiredOrder(orderId);
        if (!Objects.equals(order.getUserId(), operatorId)) {
            throw new OrderNotFoundException("订单不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, Long.parseLong(orderId))
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.FINISHED_WAIT_PAY.getCode())
                        .set(RideOrder::getOrderStatus, RideOrderStatus.PAID.getCode())
                        .set(RideOrder::getPayTime, now)
                        .set(RideOrder::getUpdateTime, now));
        return updated > 0;
    }

    @Override
    public String cancelOrder(String orderId, Long operatorId, String operatorRole,
                              Integer cancelRole, String cancelReason) {
        if (cancelRole == null) {
            throw new InvalidOrderRequestException("cancelRole不能为空");
        }
        RideOrder order = getRequiredOrder(orderId);
        Integer status = order.getOrderStatus();
        if (RideOrderStatus.PAID.getCode() == status || RideOrderStatus.CANCELLED.getCode() == status) {
            throw new InvalidOrderRequestException("当前订单状态不允许取消");
        }
        if (status >= RideOrderStatus.FINISHED_WAIT_PAY.getCode()) {
            throw new InvalidOrderRequestException("当前订单状态不允许取消");
        }

        switch (cancelRole) {
            case 1 -> {
                if (!Objects.equals(order.getUserId(), operatorId)) {
                    throw new OrderNotFoundException("订单不存在");
                }
                if (status > RideOrderStatus.USER_MAX_CANCEL_STATUS) {
                    throw new InvalidOrderRequestException("行程已开始，无法取消");
                }
            }
            case 2 ->
                    throw new InvalidOrderRequestException("司机取消本阶段未开放");
            case 3 -> {
                if (!isAdminOrSupport(operatorRole)) {
                    throw new OrderAccessDeniedException("无权限取消该订单");
                }
            }
            default -> throw new InvalidOrderRequestException("无效的取消角色");
        }

        // 违约金：仅用户取消且司机已接单，接单超 5 分钟才收
        BigDecimal cancelFee = BigDecimal.ZERO;
        if (cancelRole == 1 && order.getDriverId() != null && order.getDriverAcceptTime() != null) {
            long minutes = Duration.between(order.getDriverAcceptTime(), LocalDateTime.now()).toMinutes();
            if (minutes > 5) {
                cancelFee = calcCancelFee(minutes);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, Long.parseLong(orderId))
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getOrderStatus, status)
                        .set(RideOrder::getOrderStatus, RideOrderStatus.CANCELLED.getCode())
                        .set(RideOrder::getCancelRole, cancelRole)
                        .set(RideOrder::getCancelReason, normalizeText(cancelReason))
                        .set(RideOrder::getRealPrice, cancelFee)
                        .set(RideOrder::getPriceBase, BigDecimal.ZERO)
                        .set(RideOrder::getPriceTime, BigDecimal.ZERO)
                        .set(RideOrder::getPriceDistance, BigDecimal.ZERO)
                        .set(RideOrder::getPriceExpedited, BigDecimal.ZERO)
                        .set(RideOrder::getUpdateTime, now));
        if (updated <= 0) {
            throw new OrderStateConflictException("订单状态已变化，请刷新后重试");
        }
        return cancelFee.toPlainString();
    }

    // ============ 司机端 ============

    @Override
    public Boolean driverAcceptOrder(String orderId, Long driverId) {
        getRequiredOrder(orderId);
        Long activeCount = rideOrderMapper.selectCount(new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getDriverId, driverId)
                .eq(RideOrder::getIsDeleted, 0)
                .notIn(RideOrder::getOrderStatus, List.of(
                        RideOrderStatus.FINISHED_WAIT_PAY.getCode(),
                        RideOrderStatus.PAID.getCode(),
                        RideOrderStatus.CANCELLED.getCode())));
        if (activeCount != null && activeCount > 0) {
            throw new OrderStateConflictException("司机已有未结束订单，无法接单");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, Long.parseLong(orderId))
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
                        .isNull(RideOrder::getDriverId)
                        .set(RideOrder::getDriverId, driverId)
                        .set(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
                        .set(RideOrder::getDriverAcceptTime, now)
                        .set(RideOrder::getUpdateTime, now));
        return updated > 0;
    }

    @Override
    public Boolean driverArriveStart(String orderId, Long driverId) {
        assertDriverOwnsOrder(orderId, driverId);
        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, Long.parseLong(orderId))
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getDriverId, driverId)
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
                        .set(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ARRIVED.getCode())
                        .set(RideOrder::getDriverArriveTime, now)
                        .set(RideOrder::getUpdateTime, now));
        return updated > 0;
    }

    @Override
    public Boolean startRide(String orderId, Long driverId) {
        assertDriverOwnsOrder(orderId, driverId);
        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, Long.parseLong(orderId))
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getDriverId, driverId)
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ARRIVED.getCode())
                        .set(RideOrder::getOrderStatus, RideOrderStatus.IN_TRIP.getCode())
                        .set(RideOrder::getPickupTime, now)
                        .set(RideOrder::getUpdateTime, now));
        return updated > 0;
    }

    @Override
    public OrderBillVO finishRide(String orderId, Long driverId, BigDecimal endLat, BigDecimal endLng,
                                  String endAddress, String realPolyline, LocalDateTime arriveTime) {
        RideOrder order = getRequiredOrder(orderId);
        if (order.getDriverId() == null || !order.getDriverId().equals(driverId)) {
            throw new OrderAccessDeniedException("无权限操作该订单");
        }
        if (order.getOrderStatus() == null
                || order.getOrderStatus() != RideOrderStatus.IN_TRIP.getCode()) {
            throw new InvalidOrderRequestException("订单状态不允许结束行程");
        }
        String normalizedPolyline = normalizeText(realPolyline);
        if (normalizedPolyline == null) {
            throw new InvalidOrderRequestException("realPolyline不能为空");
        }
        String traceId = order.getMongoTraceId();
        if (traceId == null || traceId.isBlank()) {
            throw new InvalidOrderRequestException("订单未关联轨迹会话");
        }
        orderRouteService.updateRealPolyline(traceId, normalizedPolyline);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime finishTime = arriveTime != null ? arriveTime : now;

        BigDecimal finalEndLat = endLat != null ? endLat : order.getEndLat();
        BigDecimal finalEndLng = endLng != null ? endLng : order.getEndLng();
        BigDecimal realDistance = calcPolylineDistanceKm(normalizedPolyline);
        if (realDistance == null
                && order.getStartLat() != null
                && order.getStartLng() != null
                && finalEndLat != null
                && finalEndLng != null) {
            realDistance = haversineKm(order.getStartLat(), order.getStartLng(), finalEndLat, finalEndLng)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        long durationSec = 0;
        if (order.getPickupTime() != null) {
            durationSec = Duration.between(order.getPickupTime(), finishTime).getSeconds();
        }

        PriceEstimateVO priceVO = priceCalculator.calculate(
                realDistance == null ? null : realDistance.toPlainString(),
                String.valueOf(durationSec),
                order.getVehicleType(),
                order.getIsExpedited());

        LambdaUpdateWrapper<RideOrder> uw = new LambdaUpdateWrapper<RideOrder>()
                .eq(RideOrder::getOrderId, Long.parseLong(orderId))
                .eq(RideOrder::getIsDeleted, 0)
                .eq(RideOrder::getDriverId, driverId)
                .eq(RideOrder::getOrderStatus, RideOrderStatus.IN_TRIP.getCode())
                .set(RideOrder::getOrderStatus, RideOrderStatus.FINISHED_WAIT_PAY.getCode())
                .set(RideOrder::getFinishTime, finishTime)
                .set(RideOrder::getRealPrice, priceVO.estPrice())
                .set(RideOrder::getPriceBase, priceVO.estPriceBase())
                .set(RideOrder::getPriceTime, priceVO.estPriceTime())
                .set(RideOrder::getPriceDistance, priceVO.estPriceDistance())
                .set(RideOrder::getPriceExpedited, priceVO.estPriceExpedited())
                .set(RideOrder::getPriceRadio, priceVO.estPriceRadio())
                .set(RideOrder::getRealDistance, realDistance)
                .set(RideOrder::getUpdateTime, now);
        String normalizedEndAddress = normalizeText(endAddress);
        if (endLat != null) {
            uw.set(RideOrder::getEndLat, endLat);
        }
        if (endLng != null) {
            uw.set(RideOrder::getEndLng, endLng);
        }
        if (normalizedEndAddress != null) {
            uw.set(RideOrder::getEndAddress, normalizedEndAddress);
        }

        int updated = rideOrderMapper.update(null, uw);
        if (updated <= 0) {
            throw new OrderStateConflictException("订单状态已变化，请刷新后重试");
        }
        return new OrderBillVO(
                orderId,
                priceVO.estPrice(),
                priceVO.estPriceBase(),
                priceVO.estPriceTime(),
                priceVO.estPriceDistance(),
                priceVO.estPriceExpedited(),
                priceVO.estPriceRadio());
    }

    @Override
    public List<RideOrderVO> getDriverOrderTasks(Long driverId, Boolean isFinished) {
        LambdaQueryWrapper<RideOrder> qw = new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getDriverId, driverId)
                .eq(RideOrder::getIsDeleted, 0);
        if (Boolean.TRUE.equals(isFinished)) {
            LocalDate today = LocalDate.now();
            LocalDateTime start = today.atStartOfDay();
            LocalDateTime end = today.plusDays(1).atStartOfDay();
            qw.in(RideOrder::getOrderStatus, List.of(
                            RideOrderStatus.FINISHED_WAIT_PAY.getCode(),
                            RideOrderStatus.PAID.getCode()))
                    .ge(RideOrder::getFinishTime, start)
                    .lt(RideOrder::getFinishTime, end)
                    .orderByDesc(RideOrder::getFinishTime);
        } else {
            qw.in(RideOrder::getOrderStatus, List.of(
                            RideOrderStatus.DRIVER_ACCEPTED.getCode(),
                            RideOrderStatus.DRIVER_ARRIVED.getCode(),
                            RideOrderStatus.IN_TRIP.getCode(),
                            RideOrderStatus.FINISHED_WAIT_PAY.getCode()))
                    .orderByDesc(RideOrder::getUpdateTime);
        }
        return rideOrderMapper.selectList(qw).stream().map(this::toVO).toList();
    }

    @Override
    public PageResult<RideOrderVO> getDriverOrderPool(Integer page, Integer size) {
        int p = page == null ? 1 : page;
        int s = size == null ? 10 : size;
        if (p <= 0 || s <= 0) {
            throw new InvalidOrderRequestException("page/size必须为正数");
        }
        LambdaQueryWrapper<RideOrder> baseQw = new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getIsDeleted, 0)
                .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode());
        Long total = rideOrderMapper.selectCount(baseQw);
        List<RideOrder> list = rideOrderMapper.selectList(baseQw
                .orderByDesc(RideOrder::getCreateTime)
                .last("limit " + (p - 1) * s + "," + s));
        return new PageResult<>(p, s, total == null ? 0L : total,
                list.stream().map(this::toVO).toList());
    }

    @Override
    public RideOrderVO getDriverCurrentOrder(Long driverId) {
        RideOrder order = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getDriverId, driverId)
                .eq(RideOrder::getIsDeleted, 0)
                .notIn(RideOrder::getOrderStatus, List.of(
                        RideOrderStatus.FINISHED_WAIT_PAY.getCode(),
                        RideOrderStatus.PAID.getCode(),
                        RideOrderStatus.CANCELLED.getCode()))
                .orderByDesc(RideOrder::getUpdateTime)
                .last("limit 1"));
        if (order == null) {
            return null;
        }
        return toDetailVO(order, orderRouteService.getByTraceId(order.getMongoTraceId()));
    }

    private void assertDriverOwnsOrder(String orderId, Long driverId) {
        RideOrder order = getRequiredOrder(orderId);
        if (order.getDriverId() == null || !order.getDriverId().equals(driverId)) {
            throw new OrderAccessDeniedException("无权限操作该订单");
        }
    }

    private BigDecimal calcPolylineDistanceKm(String realPolyline) {
        String normalized = normalizeText(realPolyline);
        if (normalized == null) {
            return null;
        }
        String[] points = normalized.split(";");
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal prevLat = null;
        BigDecimal prevLng = null;
        boolean hasSegment = false;
        for (String point : points) {
            if (point == null || point.isBlank()) {
                continue;
            }
            String[] parts = point.trim().split(",");
            if (parts.length != 2) {
                continue;
            }
            BigDecimal lng = parseCoordinate(parts[0]);
            BigDecimal lat = parseCoordinate(parts[1]);
            if (lng == null || lat == null) {
                continue;
            }
            if (prevLat != null && prevLng != null) {
                total = total.add(haversineKm(prevLat, prevLng, lat, lng));
                hasSegment = true;
            }
            prevLat = lat;
            prevLng = lng;
        }
        return hasSegment ? total.setScale(2, RoundingMode.HALF_UP) : null;
    }

    private BigDecimal haversineKm(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue()))
                * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return BigDecimal.valueOf(r * c);
    }

    private BigDecimal parseCoordinate(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private BigDecimal calcCancelFee(long acceptedMinutes) {
        long over = Math.max(0, acceptedMinutes - 5);
        BigDecimal fee = new BigDecimal("5.00")
                .add(new BigDecimal(over).multiply(new BigDecimal("0.50")));
        if (fee.compareTo(new BigDecimal("20.00")) > 0) {
            fee = new BigDecimal("20.00");
        }
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    private void supplementAddress(CreateOrderDTO dto) {
        if (normalizeText(dto.getStartAddress()) == null) {
            dto.setStartAddress(supplementAddress(dto.getStartLng(), dto.getStartLat()));
        }
        if (normalizeText(dto.getEndAddress()) == null) {
            dto.setEndAddress(supplementAddress(dto.getEndLng(), dto.getEndLat()));
        }
    }

    private String supplementAddress(BigDecimal lng, BigDecimal lat) {
        if (lng == null || lat == null) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            try {
                Optional<Regeocode> regeocode = amapGeoRegeoService.getRegeo(lng.doubleValue(), lat.doubleValue());
                if (regeocode.isPresent()
                        && regeocode.get().formattedAddress() != null
                        && !regeocode.get().formattedAddress().isBlank()) {
                    return regeocode.get().formattedAddress();
                }
                if (i < 2) {
                    Thread.sleep(100L * (i + 1));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("逆地理编码失败，第{}次重试: {}", i + 1, e.getMessage());
                if (i < 2) {
                    try {
                        Thread.sleep(100L * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return null;
    }

    private void assertCanViewOrder(RideOrder order, Long operatorId, String operatorRole) {
        if (isAdminOrSupport(operatorRole)) {
            return;
        }
        if (order.getUserId() == null || !order.getUserId().equals(operatorId)) {
            throw new OrderNotFoundException("订单不存在");
        }
    }

    private boolean isAdminOrSupport(String role) {
        return "ADMIN".equals(role) || "SUPPORT".equals(role);
    }

    private RideOrder getRequiredOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new InvalidOrderRequestException("orderId不能为空");
        }
        RideOrder order = rideOrderMapper.selectOne(
                new LambdaQueryWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, Long.parseLong(orderId))
                        .eq(RideOrder::getIsDeleted, 0));
        if (order == null) {
            throw new OrderNotFoundException("订单不存在");
        }
        return order;
    }

    private PageResult<RideOrderVO> queryOrderPage(LambdaQueryWrapper<RideOrder> qw, int page, int size) {
        int offset = (page - 1) * size;
        Long total = rideOrderMapper.selectCount(qw);
        List<RideOrder> list = rideOrderMapper.selectList(qw
                .orderByDesc(RideOrder::getCreateTime)
                .last("limit " + offset + "," + size));
        List<RideOrderVO> records = list.stream().map(this::toVO).toList();
        return new PageResult<>(page, size, total == null ? 0L : total, records);
    }

    private RideOrderVO toDetailVO(RideOrder order, OrderRoute route) {
        return new RideOrderVO(
                order.getOrderId() != null ? order.getOrderId().toString() : null,
                order.getUserId() != null ? order.getUserId().toString() : null,
                order.getDriverId() != null ? order.getDriverId().toString() : null,
                route != null ? route.getEstPolyline() : null,
                route != null ? route.getRealPolyline() : null,
                order.getVehicleType(), order.getIsReservation(), order.getIsExpedited(),
                order.getSafetyCode(), order.getOrderStatus(), order.getCancelRole(), order.getCancelReason(),
                order.getCreateTime(), order.getScheduledTime(),
                order.getDriverAcceptTime(), order.getDriverArriveTime(),
                order.getPickupTime(), order.getFinishTime(), order.getPayTime(),
                order.getStartAddress(), order.getStartLat(), order.getStartLng(),
                order.getEndAddress(), order.getEndLat(), order.getEndLng(),
                order.getEstDistance(), order.getRealDistance(), order.getEstPrice(), order.getRealPrice(),
                order.getPriceBase(), order.getPriceTime(), order.getPriceDistance(),
                order.getPriceExpedited(), order.getPriceRadio(),
                order.getUpdateTime(), order.getIsDeleted());
    }

    private RideOrderVO toVO(RideOrder order) {
        return toDetailVO(order, null);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateSafetyCode() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(0, 10000));
    }
}
