package com.fancy.taxiagent.auth.controller;

import com.fancy.taxiagent.auth.config.AuthSecurityConfiguration;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.EmailScene;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.exception.AuthGlobalExceptionHandler;
import com.fancy.taxiagent.auth.service.AuthAccountService;
import com.fancy.taxiagent.auth.service.EmailCodeService;
import com.fancy.taxiagent.auth.service.LoginService;
import com.fancy.taxiagent.auth.service.PasswordResetService;
import com.fancy.taxiagent.auth.service.RegistrationService;
import com.fancy.taxiagent.auth.service.dto.LoginResult;
import com.fancy.taxiagent.auth.service.dto.RegisterCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开认证接口（验证码发送 + 注册 + 登录/刷新/登出/重置）的 MVC 测试。
 */
@WebMvcTest(
        controllers = AuthController.class,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        }
)
@Import({AuthSecurityConfiguration.class, AuthGlobalExceptionHandler.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailCodeService emailCodeService;

    @MockitoBean
    private RegistrationService registrationService;

    @MockitoBean
    private AuthAccountService authAccountService;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldSendEmailCode() throws Exception {
        mockMvc.perform(post("/api/auth/email-code")
                        .header("X-Trace-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@example.com", "scene": "REGISTER"}
                                """))
                .andExpect(status().isOk());
        verify(emailCodeService).sendCode("alice@example.com", EmailScene.REGISTER);
    }

    @Test
    void shouldSendLoginSceneCode() throws Exception {
        mockMvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@example.com", "scene": "LOGIN"}
                                """))
                .andExpect(status().isOk());
        verify(emailCodeService).sendCode("alice@example.com", EmailScene.LOGIN);
    }

    @Test
    void shouldRejectUnsupportedScene() throws Exception {
        mockMvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@example.com", "scene": "BOGUS"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldRejectResetPasswordForUnknownAccount() throws Exception {
        mockMvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ghost@example.com", "scene": "RESET_PASSWORD"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        verify(emailCodeService, never()).sendCode(any(), any());
    }

    @Test
    void shouldSendResetPasswordCodeForExistingAccount() throws Exception {
        when(authAccountService.findActiveByEmail("alice@example.com"))
                .thenReturn(new AuthAccount());

        mockMvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@example.com", "scene": "RESET_PASSWORD"}
                                """))
                .andExpect(status().isOk());
        verify(emailCodeService).sendCode("alice@example.com", EmailScene.RESET_PASSWORD);
    }

    @Test
    void shouldRegisterAndReturnToken() throws Exception {
        LoginResult result = new LoginResult("jwt-token", "rt-token", "Bearer", 900, 604800, 10001L, "alice", "USER");
        when(registrationService.register(any(RegisterCommand.class))).thenReturn(result);

        mockMvc.perform(post("/api/auth/register")
                        .header("X-Trace-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@example.com", "code": "123456", "password": "secret", "username": "alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("rt-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSec").value(900))
                .andExpect(jsonPath("$.refreshExpiresInSec").value(604800))
                .andExpect(jsonPath("$.userId").value("10001"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void shouldRegisterReturningRefreshToken() throws Exception {
        LoginResult result = new LoginResult("jwt-token", "rt-token", "Bearer", 900, 604800, 10001L, "alice", "USER");
        when(registrationService.register(any(RegisterCommand.class))).thenReturn(result);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "a@b.com", "code": "123456", "password": "secret", "username": "alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("rt-token"));
    }

    @Test
    void shouldRejectEmptyPassword() throws Exception {
        // 偏差说明：空密码校验由 RegistrationService 在服务层完成（Task 6），控制器本身不校验。
        // 若不 stub，mock 默认返回 null 会使控制器 NPE → 500；此处 stub 服务层的拒绝行为，
        // 验证控制器将 INVALID_PASSWORD 正确映射为 400。
        when(registrationService.register(any(RegisterCommand.class)))
                .thenThrow(new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码不能为空"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@example.com", "code": "123456", "password": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test
    void shouldLoginByPassword() throws Exception {
        LoginResult result = new LoginResult("jwt-token", "rt-token", "Bearer", 900, 604800, 10001L, "alice", "USER");
        when(loginService.loginByPassword("alice@example.com", "secret")).thenReturn(result);

        mockMvc.perform(post("/api/auth/login/password")
                        .header("X-Trace-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login": "alice@example.com", "password": "secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("rt-token"))
                .andExpect(jsonPath("$.userId").value("10001"));
    }

    @Test
    void shouldLoginByEmailCode() throws Exception {
        LoginResult result = new LoginResult("jwt-token", "rt-token", "Bearer", 900, 604800, 10001L, "alice", "USER");
        when(loginService.loginByEmailCode("alice@example.com", "123456")).thenReturn(result);

        mockMvc.perform(post("/api/auth/login/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@example.com", "code": "123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("rt-token"));
    }

    @Test
    void shouldRefresh() throws Exception {
        LoginResult result = new LoginResult("new-jwt", "new-rt", "Bearer", 900, 604800, 10001L, "alice", "USER");
        when(loginService.refresh("rt-token")).thenReturn(result);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "rt-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("new-rt"));
    }

    @Test
    void shouldLogoutWithToken() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .subject("10001")
                .claim("role", "USER")
                .claim("token_version", 0)
                .claim("token_type", "access")
                .claim("jti", "jti-1")
                .expiresAt(Instant.now().plusSeconds(600))
                .issuedAt(Instant.now())
                .build();
        when(jwtDecoder.decode("token-value")).thenReturn(jwt);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer token-value"))
                .andExpect(status().isOk());

        verify(loginService).logout(eq(10001L), eq("jti-1"), anyLong());
    }

    @Test
    void shouldRejectLogoutWithoutBearerToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        verify(loginService, never()).logout(any(), any(), anyLong());
    }

    @Test
    void shouldReportUsernameAvailability() throws Exception {
        when(loginService.isUsernameAvailable("alice")).thenReturn(true);

        mockMvc.perform(get("/api/auth/username/available").param("username", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void shouldResetPassword() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "a@b.com", "code": "123456", "newPassword": "newpass"}
                                """))
                .andExpect(status().isOk());

        verify(passwordResetService).reset("a@b.com", "123456", "newpass");
    }

}
