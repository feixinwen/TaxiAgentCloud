package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.UserServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.PoiView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

/**
 * 用户兴趣点详情工具：按主键查询当前用户的单个常用地点。
 *
 * <p>user-service 详情接口按主键查询（{@code GET /api/users/poi/{id}}），id 取自
 * listUserPOIs 返回结果。上游 4xx 拒绝映射为 {@code USER_REQUEST_REJECTED}，
 * 5xx 与可重试失败映射为 {@code USER_UNAVAILABLE}（照 RagServiceClient 错误映射）；
 * 其余失败返回 "Tool execution failed: " 开头的说明。</p>
 */
@Component
public class UserPoiDetailTool implements AgentTool {

    private final UserServiceFeignClient userServiceFeignClient;
    private final ObjectMapper objectMapper;

    public UserPoiDetailTool(UserServiceFeignClient userServiceFeignClient, ObjectMapper objectMapper) {
        this.userServiceFeignClient = userServiceFeignClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "getUserPOIDetail";
    }

    @Override
    public String description() {
        return "查询当前用户单个常用地点的详情。参数：id（地点主键，来自 listUserPOIs 返回的 id）。";
    }

    @Override
    public Class<?> inputType() {
        return Arguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        try {
            Arguments arguments = objectMapper.readValue(jsonArgs, Arguments.class);
            PoiView poi = userServiceFeignClient.getPoi(context.authorization(), context.traceId(), arguments.id());
            return objectMapper.writeValueAsString(poi);
        } catch (RetryableException exception) {
            throw unavailable(exception);
        } catch (FeignException exception) {
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

    /**
     * 详情工具参数。
     *
     * @param id 地点主键（来自 listUserPOIs 返回的 id）
     */
    public record Arguments(Long id) {
    }
}
