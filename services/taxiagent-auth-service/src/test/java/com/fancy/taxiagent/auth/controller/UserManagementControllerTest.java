package com.fancy.taxiagent.auth.controller;

import com.fancy.taxiagent.auth.config.AuthSecurityConfiguration;
import com.fancy.taxiagent.auth.exception.AuthGlobalExceptionHandler;
import com.fancy.taxiagent.auth.service.AdminUserService;
import com.fancy.taxiagent.auth.service.CurrentUserService;
import com.fancy.taxiagent.auth.service.dto.AdminUserPageView;
import com.fancy.taxiagent.auth.service.dto.CurrentUserView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理接口的 MVC 测试：
 * 当前用户三件套（/api/users/current/**）需要有效 JWT，userId 取自 JWT subject；
 * 管理员接口（/api/users/admin/**）额外要求 ADMIN 角色（{@code @PreAuthorize}）。
 */
@WebMvcTest(
        controllers = UserManagementController.class,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        }
)
@Import({AuthSecurityConfiguration.class, AuthGlobalExceptionHandler.class})
class UserManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private AdminUserService adminUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldReturnCurrentUser() throws Exception {
        CurrentUserView view = new CurrentUserView("10001", "me", "me@example.com", "USER", 1,
                LocalDateTime.now(), LocalDateTime.now());
        when(currentUserService.getCurrent(10001L)).thenReturn(view);
        jwt("10001", "USER");

        mockMvc.perform(get("/api/users/current")
                        .header("Authorization", "Bearer token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("10001"))
                .andExpect(jsonPath("$.username").value("me"))
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value(1));
    }

    @Test
    void shouldUpdateCurrentUser() throws Exception {
        CurrentUserView view = new CurrentUserView("10001", "newname", "new@example.com", "USER", 1,
                LocalDateTime.now(), LocalDateTime.now());
        when(currentUserService.updateCurrent(10001L, "newname", "new@example.com")).thenReturn(view);
        jwt("10001", "USER");

        mockMvc.perform(post("/api/users/current/update")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "newname", "email": "new@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newname"))
                .andExpect(jsonPath("$.email").value("new@example.com"));

        verify(currentUserService).updateCurrent(10001L, "newname", "new@example.com");
    }

    @Test
    void shouldChangePassword() throws Exception {
        jwt("10001", "USER");

        mockMvc.perform(post("/api/users/current/password/reset")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password": "newpass"}
                                """))
                .andExpect(status().isOk());

        verify(currentUserService).changePassword(10001L, "newpass");
    }

    @Test
    void shouldRejectWithoutToken() throws Exception {
        // 无 Token 时由 Spring Security 资源服务器入口点直接返回 401（请求未到达控制器，无 JSON body）
        mockMvc.perform(get("/api/users/current"))
                .andExpect(status().isUnauthorized());
        verify(currentUserService, never()).getCurrent(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldRejectMalformedSubject() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .subject("not-a-number")
                .claim("role", "USER")
                .claim("token_version", 0)
                .claim("token_type", "access")
                .claim("jti", "jti-2")
                .expiresAt(Instant.now().plusSeconds(600))
                .issuedAt(Instant.now())
                .build();
        when(jwtDecoder.decode("token-value")).thenReturn(jwt);

        mockMvc.perform(get("/api/users/current")
                        .header("Authorization", "Bearer token-value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        verify(currentUserService, never()).getCurrent(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldCreateUserAsAdmin() throws Exception {
        when(adminUserService.create("newuser", "secret", "SUPPORT")).thenReturn(20001L);
        jwt("10001", "ADMIN");

        mockMvc.perform(post("/api/users/admin/create")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "newuser", "password": "secret", "role": "SUPPORT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("20001"));

        verify(adminUserService).create("newuser", "secret", "SUPPORT");
    }

    @Test
    void shouldUpdateUserAsAdmin() throws Exception {
        jwt("10001", "ADMIN");

        mockMvc.perform(post("/api/users/admin/update")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 20001, "username": "newname", "role": "DRIVER"}
                                """))
                .andExpect(status().isOk());

        verify(adminUserService).update(20001L, "newname", null, null, "DRIVER");
    }

    @Test
    void shouldPageUsersAsAdmin() throws Exception {
        AdminUserPageView page = new AdminUserPageView(1, List.of(
                new AdminUserPageView.AdminUserRow("20001", "alice", "alice@example.com", "USER", 1,
                        LocalDateTime.now())));
        when(adminUserService.page("al", "USER", null, 1, 10)).thenReturn(page);
        jwt("10001", "ADMIN");

        mockMvc.perform(post("/api/users/admin/page")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "al", "role": "USER", "pageNum": 1, "pageSize": 10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].email").value("alice@example.com"));

        verify(adminUserService).page("al", "USER", null, 1, 10);
    }

    @Test
    void shouldBatchStatusAsAdmin() throws Exception {
        when(adminUserService.batchStatus(List.of(20001L, 20002L), "DISABLE")).thenReturn(2);
        jwt("10001", "ADMIN");

        mockMvc.perform(post("/api/users/admin/batch/status")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds": [20001, 20002], "action": "DISABLE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(2));

        verify(adminUserService).batchStatus(List.of(20001L, 20002L), "DISABLE");
    }

    @Test
    void shouldRejectAdminEndpointForNonAdmin() throws Exception {
        // 非 ADMIN 角色的有效 JWT：方法级 @PreAuthorize("hasRole('ADMIN')") 直接拒绝（403）
        jwt("10001", "USER");

        mockMvc.perform(post("/api/users/admin/create")
                        .header("Authorization", "Bearer token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "newuser", "password": "secret", "role": "SUPPORT"}
                                """))
                .andExpect(status().isForbidden());

        verify(adminUserService, never()).create(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldRejectAdminEndpointWithoutToken() throws Exception {
        // 无 Token：资源服务器入口点 401
        mockMvc.perform(post("/api/users/admin/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "newuser", "password": "secret", "role": "SUPPORT"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private Jwt jwt(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", role)
                .claim("token_version", 0)
                .claim("token_type", "access")
                .claim("jti", "jti-1")
                .expiresAt(Instant.now().plusSeconds(600))
                .issuedAt(Instant.now())
                .build();
        when(jwtDecoder.decode("token-value")).thenReturn(jwt);
        return jwt;
    }
}
