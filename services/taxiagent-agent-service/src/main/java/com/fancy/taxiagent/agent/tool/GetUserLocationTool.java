package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.UserServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.UserLocationView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

/**
 * 用户当前位置工具：查询用户保存在云端的定位（前端定位按钮/地图点选写入）。
 *
 * <p>用户未明说起点时的默认起点来源；用户明确说出的起点优先于保存的位置。
 * 未设置位置（上游 404）是正常状态，返回引导文本而非异常；
 * 其余 4xx 映射为 {@code USER_REQUEST_REJECTED}，5xx 与可重试失败映射为
 * {@code USER_UNAVAILABLE}（照 UserPoiTool 错误映射）。</p>
 */
@Component
public class GetUserLocationTool implements AgentTool {

    private static final String LOCATION_NOT_SET_GUIDANCE =
            "用户尚未设置位置。请提示用户：可点击地图上的定位按钮设置位置，或直接告诉我起点地址。";

    private final UserServiceFeignClient userServiceFeignClient;
    private final ObjectMapper objectMapper;

    public GetUserLocationTool(UserServiceFeignClient userServiceFeignClient, ObjectMapper objectMapper) {
        this.userServiceFeignClient = userServiceFeignClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "getUserLocation";
    }

    @Override
    public String description() {
        return "查询当前用户保存在云端的当前位置（来自地图定位），无参数。"
                + "返回 latitude/longitude/address。用户未明说起点时，用它作为默认起点。";
    }

    @Override
    public Class<?> inputType() {
        return VoidArguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        try {
            UserLocationView location =
                    userServiceFeignClient.getLocation(context.authorization(), context.traceId());
            return objectMapper.writeValueAsString(location);
        } catch (RetryableException exception) {
            throw unavailable(exception);
        } catch (FeignException exception) {
            if (exception.status() == 404) {
                return LOCATION_NOT_SET_GUIDANCE;
            }
            if (exception.status() >= 400 && exception.status() < 500) {
                throw new AgentExecutionException("USER_REQUEST_REJECTED", "用户请求被拒绝", exception);
            }
            throw unavailable(exception);
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
    }

    private AgentExecutionException unavailable(Throwable cause) {
        return new AgentExecutionException("USER_UNAVAILABLE", "用户服务暂不可用", cause);
    }

    /** 当前位置工具无参参数。 */
    public record VoidArguments() {
    }
}
