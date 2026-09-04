package com.fancy.taxiagent.user.controller;

import com.fancy.taxiagent.user.domain.enums.BatchAction;
import com.fancy.taxiagent.user.domain.enums.UserRole;
import com.fancy.taxiagent.user.dto.UserProfilePageView;
import com.fancy.taxiagent.user.dto.UserProfileView;
import com.fancy.taxiagent.user.exception.UserProfileConflictException;
import com.fancy.taxiagent.user.exception.UserProfileNotFoundException;
import com.fancy.taxiagent.user.filter.RequestTraceFilter;
import com.fancy.taxiagent.user.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 User Service 内部用户资料接口的 HTTP 契约和异常映射。
 */
@WebMvcTest(
        controllers = InternalUserProfileController.class,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        }
)
@Import({
        UserApiExceptionHandler.class,
        RequestTraceFilter.class,
        InternalUserProfileControllerTest.PermitInternalSecurityConfiguration.class
})
class InternalUserProfileControllerTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 7, 11, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 8, 7, 11, 5);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService userProfileService;

    @Test
    void shouldCreateProfileIdempotentlyAndPreserveTraceId() throws Exception {
        when(userProfileService.createProfile(any())).thenReturn(profileView());

        mockMvc.perform(put("/internal/users/10001/profile")
                        .header(RequestTraceFilter.TRACE_ID_HEADER, "trace-create-10001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "first-user",
                                  "role": "user"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestTraceFilter.TRACE_ID_HEADER, "trace-create-10001"))
                .andExpect(jsonPath("$.userId").value(10001))
                .andExpect(jsonPath("$.username").value("first-user"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value(1));
    }

    @Test
    void shouldResolveProfileByUsernameAndRole() throws Exception {
        when(userProfileService.resolveProfile("first-user", UserRole.USER)).thenReturn(profileView());

        mockMvc.perform(get("/internal/users/resolve")
                        .param("username", "first-user")
                        .param("role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(10001))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void shouldRejectBlankCreateRequest() throws Exception {
        mockMvc.perform(put("/internal/users/10001/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": " ",
                                  "role": "USER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("用户名不能为空"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void shouldRejectUnsupportedRole() throws Exception {
        mockMvc.perform(put("/internal/users/10001/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "first-user",
                                  "role": "UNKNOWN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_USER_PROFILE"))
                .andExpect(jsonPath("$.message").value("用户角色不合法"));
    }

    @Test
    void shouldReturnConflictResponse() throws Exception {
        when(userProfileService.createProfile(any()))
                .thenThrow(new UserProfileConflictException("用户名已存在"));

        mockMvc.perform(put("/internal/users/10002/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "first-user",
                                  "role": "USER"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void shouldReturnNotFoundResponse() throws Exception {
        when(userProfileService.getProfile(99999L)).thenThrow(new UserProfileNotFoundException(99999L));

        mockMvc.perform(get("/internal/users/99999/profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("用户资料不存在: userId=99999"));
    }

    @Test
    void shouldPageProfilesWithFilters() throws Exception {
        when(userProfileService.pageUsers(eq("ali"), eq(UserRole.USER), eq(0), eq(1), eq(10)))
                .thenReturn(new UserProfilePageView(1, java.util.List.of(profileView())));

        mockMvc.perform(get("/internal/users/page")
                        .param("username", "ali")
                        .param("role", "user")
                        .param("deleted", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].userId").value(10001))
                .andExpect(jsonPath("$.records[0].role").value("USER"));
    }

    @Test
    void shouldUpdateUsername() throws Exception {
        when(userProfileService.updateUsername(10001L, "renamed"))
                .thenReturn(new UserProfileView(
                        10001L, "renamed", UserRole.USER, 1, CREATED_AT, UPDATED_AT));

        mockMvc.perform(patch("/internal/users/10001/username")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "renamed"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("renamed"));
    }

    @Test
    void shouldUpdateRole() throws Exception {
        when(userProfileService.updateRole(10001L, UserRole.SUPPORT))
                .thenReturn(new UserProfileView(
                        10001L, "first-user", UserRole.SUPPORT, 1, CREATED_AT, UPDATED_AT));

        mockMvc.perform(patch("/internal/users/10001/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "SUPPORT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPPORT"));
    }

    @Test
    void shouldBatchStatusChange() throws Exception {
        when(userProfileService.batchStatus(java.util.List.of(10001L, 10002L), BatchAction.DISABLE))
                .thenReturn(2);

        mockMvc.perform(post("/internal/users/batch/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userIds": [10001, 10002],
                                  "action": "DISABLE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(2));
    }

    private UserProfileView profileView() {
        return new UserProfileView(
                10001L,
                "first-user",
                UserRole.USER,
                1,
                CREATED_AT,
                UPDATED_AT
        );
    }

    /**
     * 仅在 Controller 切片测试中放行内部路径，使该测试聚焦 HTTP 契约而不是重复验证生产安全策略。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class PermitInternalSecurityConfiguration {

        @Bean
        @Order(0)
        SecurityFilterChain testInternalSecurityFilterChain(HttpSecurity http) throws Exception {
            http
                    .securityMatcher("/internal/**")
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
            return http.build();
        }
    }
}
