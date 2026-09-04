package com.fancy.taxiagent.user.poi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.user.domain.entity.UserPoi;
import com.fancy.taxiagent.user.mapper.UserPoiMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户常用地点（POI）服务。
 *
 * <p>归属是核心安全约束：所有按 id 的操作一律以 {@code id + user_id} 联合查询，越权或不存在
 * 统一返回 {@link PoiNotFoundException}（404），不暴露数据是否存在。</p>
 */
@Service
public class PoiService {

    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final Logger log = LoggerFactory.getLogger(PoiService.class);

    private final UserPoiMapper userPoiMapper;

    public PoiService(UserPoiMapper userPoiMapper) {
        this.userPoiMapper = userPoiMapper;
    }

    public List<PoiView> list(Long userId) {
        return userPoiMapper.selectList(new LambdaQueryWrapper<UserPoi>()
                        .eq(UserPoi::getUserId, userId)
                        .orderByDesc(UserPoi::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 按 id 与归属查询单个 POI；不存在或不属于该用户时返回 404。
     */
    public PoiView get(Long userId, Long poiId) {
        return toView(selectOwned(userId, poiId));
    }

    /**
     * 新增 POI，userId 由调用方（JWT subject）填充，id 由数据库自增生成。
     */
    public PoiView create(Long userId, PoiView input) {
        UserPoi entity = new UserPoi();
        entity.setUserId(userId);
        applyInput(entity, input);
        userPoiMapper.insert(entity);
        log.info("event=user_poi_created userId={} poiId={}", userId, entity.getId());
        // 回读数据库默认生成的 created_at/updated_at
        return toView(userPoiMapper.selectById(entity.getId()));
    }

    /**
     * 更新 POI 的标签/名称/地址/坐标；必须先通过归属校验。
     */
    public void update(Long userId, Long poiId, PoiView input) {
        UserPoi existing = selectOwned(userId, poiId);
        applyInput(existing, input);
        existing.setUpdatedAt(LocalDateTime.now());
        userPoiMapper.updateById(existing);
        log.info("event=user_poi_updated userId={} poiId={}", userId, poiId);
    }

    /**
     * 逻辑删除 POI；必须先通过归属校验。
     */
    public void delete(Long userId, Long poiId) {
        selectOwned(userId, poiId);
        userPoiMapper.deleteById(poiId);
        log.info("event=user_poi_deleted userId={} poiId={}", userId, poiId);
    }

    /**
     * 家/公司/其他分组视图，供下单流程使用。
     *
     * <p>与单体行为一致：按 id 倒序迭代，同标签"家"或"公司"的后者覆盖前者，
     * 最终保留最早创建的一个；其余标签进入 other 列表。</p>
     */
    public PoiOrderView orderView(Long userId) {
        List<UserPoi> pois = userPoiMapper.selectList(new LambdaQueryWrapper<UserPoi>()
                .eq(UserPoi::getUserId, userId)
                .orderByDesc(UserPoi::getId));
        PoiView home = null;
        PoiView work = null;
        List<PoiView> other = new ArrayList<>();
        for (UserPoi poi : pois) {
            if ("家".equals(poi.getPoiTag())) {
                home = toView(poi);
            } else if ("公司".equals(poi.getPoiTag())) {
                work = toView(poi);
            } else {
                other.add(toView(poi));
            }
        }
        return new PoiOrderView(home, work, other);
    }

    private UserPoi selectOwned(Long userId, Long poiId) {
        UserPoi poi = userPoiMapper.selectOne(new LambdaQueryWrapper<UserPoi>()
                .eq(UserPoi::getId, poiId)
                .eq(UserPoi::getUserId, userId)
                .last("LIMIT 1"));
        if (poi == null) {
            throw new PoiNotFoundException();
        }
        return poi;
    }

    private void applyInput(UserPoi entity, PoiView input) {
        validate(input);
        entity.setPoiTag(input.poiTag().trim());
        entity.setPoiName(input.poiName().trim());
        entity.setPoiAddress(input.poiAddress() == null ? null : input.poiAddress().trim());
        entity.setLongitude(input.longitude());
        entity.setLatitude(input.latitude());
    }

    private void validate(PoiView input) {
        if (input == null) {
            throw new InvalidPoiException("POI 不能为空");
        }
        if (input.poiTag() == null || input.poiTag().isBlank()) {
            throw new InvalidPoiException("标签不能为空");
        }
        if (input.poiName() == null || input.poiName().isBlank()) {
            throw new InvalidPoiException("地点名称不能为空");
        }
        if (input.longitude() == null || input.latitude() == null) {
            throw new InvalidPoiException("经纬度不能为空");
        }
        if (input.longitude().compareTo(MAX_LONGITUDE) > 0 || input.longitude().compareTo(MIN_LONGITUDE) < 0) {
            throw new InvalidPoiException("经度必须在 [-180, 180] 之间");
        }
        if (input.latitude().compareTo(MAX_LATITUDE) > 0 || input.latitude().compareTo(MIN_LATITUDE) < 0) {
            throw new InvalidPoiException("纬度必须在 [-90, 90] 之间");
        }
    }

    private PoiView toView(UserPoi poi) {
        return new PoiView(
                poi.getId(),
                poi.getPoiTag(),
                poi.getPoiName(),
                poi.getPoiAddress(),
                poi.getLongitude(),
                poi.getLatitude(),
                poi.getCreatedAt());
    }
}
