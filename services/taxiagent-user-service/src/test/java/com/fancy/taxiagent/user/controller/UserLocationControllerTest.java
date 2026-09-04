package com.fancy.taxiagent.user.controller;

import com.fancy.taxiagent.user.location.LocationNotFoundException;
import com.fancy.taxiagent.user.location.UserLocationService;
import com.fancy.taxiagent.user.location.UserLocationView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 位置接口的请求处理、userId 提取与错误映射验证。
 */
@WebMvcTest(controllers = UserLocationController.class)
@Import(UserPublicApiExceptionHandler.class)
class UserLocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserLocationService userLocationService;

    @Test
    void shouldGetOwnLocation() throws Exception {
        when(userLocationService.get(50001L))
                .thenReturn(new UserLocationView("31.2304", "121.4737", "上海市"));

        mockMvc.perform(get("/api/users/loc").with(user(50001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value("31.2304"))
                .andExpect(jsonPath("$.address").value("上海市"));
    }

    @Test
    void shouldReturn404WhenNoLocation() throws Exception {
        when(userLocationService.get(50001L)).thenThrow(new LocationNotFoundException());

        mockMvc.perform(get("/api/users/loc").with(user(50001L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
    }

    @Test
    void shouldSaveLocation() throws Exception {
        mockMvc.perform(post("/api/users/loc")
                        .with(user(50001L))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": "31.2304", "longitude": "121.4737", "address": "上海市"}
                                """))
                .andExpect(status().isOk());

        verify(userLocationService).save(eq(50001L), any(UserLocationView.class));
    }

    @Test
    void shouldReturn401WithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/loc"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user(Long userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(String.valueOf(userId))
                .claim("role", "USER")
                .claim("token_type", "access")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
        return authentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
