package com.fancy.taxiagent.user.location;

import com.fancy.taxiagent.user.config.UserLocationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户当前位置服务（短期状态）。
 *
 * <p>位置存储于 Redis hash，带 TTL 自动过期；坐标保存为字符串，读取时原样返回。</p>
 */
@Service
public class UserLocationService {

    private static final String LOCATION_KEY_PREFIX = "user:loc:";
    private static final Logger log = LoggerFactory.getLogger(UserLocationService.class);

    private final StringRedisTemplate redisTemplate;
    private final UserLocationProperties properties;

    public UserLocationService(StringRedisTemplate redisTemplate, UserLocationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public UserLocationView get(Long userId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key(userId));
        if (entries.isEmpty()) {
            throw new LocationNotFoundException();
        }
        return new UserLocationView(
                stringValue(entries, "latitude"),
                stringValue(entries, "longitude"),
                stringValue(entries, "address"));
    }

    public void save(Long userId, UserLocationView location) {
        validate(location);
        String key = key(userId);
        redisTemplate.opsForHash().put(key, "latitude", location.latitude());
        redisTemplate.opsForHash().put(key, "longitude", location.longitude());
        if (location.address() != null && !location.address().isBlank()) {
            redisTemplate.opsForHash().put(key, "address", location.address().trim());
        }
        redisTemplate.expire(key, properties.getTtlSeconds(), TimeUnit.SECONDS);
        log.info("event=user_location_saved userId={}", userId);
    }

    private void validate(UserLocationView location) {
        if (location == null || location.latitude() == null || location.longitude() == null) {
            throw new InvalidLocationException("经纬度不能为空");
        }
        try {
            double latitude = Double.parseDouble(location.latitude());
            double longitude = Double.parseDouble(location.longitude());
            if (latitude < -90 || latitude > 90) {
                throw new InvalidLocationException("纬度必须在 [-90, 90] 之间");
            }
            if (longitude < -180 || longitude > 180) {
                throw new InvalidLocationException("经度必须在 [-180, 180] 之间");
            }
        } catch (NumberFormatException exception) {
            throw new InvalidLocationException("经纬度格式不正确");
        }
    }

    private String key(Long userId) {
        return LOCATION_KEY_PREFIX + userId;
    }

    private String stringValue(Map<Object, Object> entries, String field) {
        Object value = entries.get(field);
        return value == null ? null : value.toString();
    }
}
