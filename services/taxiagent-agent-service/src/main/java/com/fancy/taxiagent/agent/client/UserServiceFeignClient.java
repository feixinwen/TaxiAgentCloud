package com.fancy.taxiagent.agent.client;

import com.fancy.taxiagent.agent.client.dto.PoiView;
import com.fancy.taxiagent.agent.client.dto.UserLocationView;
import com.fancy.taxiagent.agent.config.AgentFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * User Service 的 Feign 调用契约（只读兴趣点与当前位置接口）。
 */
@FeignClient(
        name = "taxiagent-user-service",
        configuration = AgentFeignConfiguration.class
)
public interface UserServiceFeignClient {

    /**
     * 携带调用方显式提供的认证与链路标识查询当前用户全部兴趣点。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @return 兴趣点列表
     */
    @GetMapping("/api/users/poi")
    List<PoiView> listPois(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId
    );

    /**
     * 携带调用方显式提供的认证与链路标识查询单个兴趣点。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param id 兴趣点主键
     * @return 兴趣点
     */
    @GetMapping("/api/users/poi/{id}")
    PoiView getPoi(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId,
            @PathVariable("id") Long id
    );

    /**
     * 携带调用方显式提供的认证与链路标识查询当前用户保存的位置；未设置时上游返回 404。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @return 当前位置
     */
    @GetMapping("/api/users/loc")
    UserLocationView getLocation(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId
    );
}
