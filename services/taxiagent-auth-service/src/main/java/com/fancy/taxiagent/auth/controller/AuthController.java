package com.fancy.taxiagent.auth.controller;

import com.fancy.taxiagent.auth.controller.dto.EmailCodeLoginRequest;
import com.fancy.taxiagent.auth.controller.dto.LoginResponse;
import com.fancy.taxiagent.auth.controller.dto.PasswordLoginRequest;
import com.fancy.taxiagent.auth.controller.dto.RefreshTokenRequest;
import com.fancy.taxiagent.auth.controller.dto.RegisterRequest;
import com.fancy.taxiagent.auth.controller.dto.ResetPasswordRequest;
import com.fancy.taxiagent.auth.controller.dto.SendEmailCodeRequest;
import com.fancy.taxiagent.auth.domain.enums.EmailScene;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.service.AuthAccountService;
import com.fancy.taxiagent.auth.service.EmailCodeService;
import com.fancy.taxiagent.auth.service.LoginService;
import com.fancy.taxiagent.auth.service.PasswordResetService;
import com.fancy.taxiagent.auth.service.RegistrationService;
import com.fancy.taxiagent.auth.service.dto.LoginResult;
import com.fancy.taxiagent.auth.service.dto.RegisterCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * 公开认证接口：验证码发送 + 注册 + 密码/验证码登录 + 刷新 + 登出 + 密码重置 + 用户名可用性。
 *
 * <p>验证码场景：REGISTER / LOGIN / RESET_PASSWORD。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final EmailCodeService emailCodeService;
    private final RegistrationService registrationService;
    private final AuthAccountService authAccountService;
    private final LoginService loginService;
    private final PasswordResetService passwordResetService;
    private final JwtDecoder jwtDecoder;

    public AuthController(EmailCodeService emailCodeService,
                          @Lazy RegistrationService registrationService,
                          AuthAccountService authAccountService,
                          @Lazy LoginService loginService,
                          @Lazy PasswordResetService passwordResetService,
                          JwtDecoder jwtDecoder) {
        this.emailCodeService = emailCodeService;
        this.registrationService = registrationService;
        this.authAccountService = authAccountService;
        this.loginService = loginService;
        this.passwordResetService = passwordResetService;
        this.jwtDecoder = jwtDecoder;
    }

    @PostMapping("/email-code")
    public void sendEmailCode(@RequestBody SendEmailCodeRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "邮箱不能为空");
        }
        EmailScene scene = switch (request.scene() == null ? "" : request.scene().toUpperCase(Locale.ROOT)) {
            case "REGISTER" -> EmailScene.REGISTER;
            case "LOGIN" -> EmailScene.LOGIN;
            case "RESET_PASSWORD" -> EmailScene.RESET_PASSWORD;
            default -> throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "不支持的验证码场景");
        };
        if (scene == EmailScene.RESET_PASSWORD
                && authAccountService.findActiveByEmail(request.email().trim().toLowerCase(Locale.ROOT)) == null) {
            throw new AuthApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "账号不存在");
        }
        emailCodeService.sendCode(request.email(), scene);
        log.info("event=auth_email_code_requested scene={}", scene);
    }

    @PostMapping("/register")
    public LoginResponse register(@RequestBody RegisterRequest request) {
        if (request == null) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求不能为空");
        }
        return toLoginResponse(registrationService.register(new RegisterCommand(
                request.email(), request.code(), request.password(), request.username(), request.role())));
    }

    @PostMapping("/login/password")
    public LoginResponse loginByPassword(@RequestBody PasswordLoginRequest request) {
        if (request == null || request.login() == null || request.login().isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "登录标识不能为空");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "密码不能为空");
        }
        return toLoginResponse(loginService.loginByPassword(request.login(), request.password()));
    }

    @PostMapping("/login/email-code")
    public LoginResponse loginByEmailCode(@RequestBody EmailCodeLoginRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "邮箱不能为空");
        }
        if (request.code() == null || request.code().isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "验证码不能为空");
        }
        return toLoginResponse(loginService.loginByEmailCode(request.email(), request.code()));
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody RefreshTokenRequest request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "登录已过期，请重新登录");
        }
        return toLoginResponse(loginService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest httpRequest) {
        String token = extractBearerToken(httpRequest);
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登录已过期，请重新登录");
        }
        Long userId = Long.valueOf(jwt.getSubject());
        String jti = jwt.getId();
        long remainingSeconds = Duration.between(Instant.now(), jwt.getExpiresAt()).getSeconds();
        loginService.logout(userId, jti, Math.max(remainingSeconds, 1));
    }

    @PostMapping("/password/reset")
    public void resetPassword(@RequestBody ResetPasswordRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "邮箱不能为空");
        }
        if (request.code() == null || request.code().isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "验证码不能为空");
        }
        passwordResetService.reset(request.email(), request.code(), request.newPassword());
    }

    @GetMapping("/username/available")
    public Map<String, Boolean> usernameAvailable(@RequestParam("username") String username) {
        if (username == null || username.isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "用户名不能为空");
        }
        if (username.contains("@")) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "用户名不能包含@符号");
        }
        return Map.of("available", loginService.isUsernameAvailable(username));
    }

    private LoginResponse toLoginResponse(LoginResult result) {
        return new LoginResponse(
                result.accessToken(), result.refreshToken(), result.tokenType(),
                result.expiresInSec(), result.refreshExpiresInSec(),
                String.valueOf(result.userId()), result.username(), result.role());
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登录已过期，请重新登录");
        }
        return header.substring(7).trim();
    }
}
