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

import java.util.List;

/**
 * 用户兴趣点列表工具：查询当前用户的全部常用地点。
 *
 * <p>上游 4xx 拒绝映射为 {@code USER_REQUEST_REJECTED}，5xx 与可重试失败映射为
 * {@code USER_UNAVAILABLE}（照 RagServiceClient 错误映射）；其余失败返回
 * "Tool execution failed: " 开头的说明。</p>
 */
@Component
public class UserPoiTool implements AgentTool {

    private final UserServiceFeignClient userServiceFeignClient;
    private final ObjectMapper objectMapper;

    public UserPoiTool(UserServiceFeignClient userServiceFeignClient, ObjectMapper objectMapper) {
        this.userServiceFeignClient = userServiceFeignClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "listUserPOIs";
    }

    @Override
    public String description() {
        return "查询当前用户的常用地点列表，无参数。返回每个地点的 id、poiTag（如 家/公司）、poiName、poiAddress、经纬度。";
    }

    @Override
    public Class<?> inputType() {
        return VoidArguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        try {
            List<PoiView> pois = userServiceFeignClient.listPois(context.authorization(), context.traceId());
            return objectMapper.writeValueAsString(pois);
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

    /** 常用地点列表工具无参参数。 */
    public record VoidArguments() {
    }
}
