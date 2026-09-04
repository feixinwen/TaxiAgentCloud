package com.fancy.taxiagent.user.controller;

import com.fancy.taxiagent.user.config.UserSecurityConfiguration;
import com.fancy.taxiagent.user.config.UserSecurityFailureHandler;
import com.fancy.taxiagent.user.poi.InvalidPoiException;
import com.fancy.taxiagent.user.poi.PoiNotFoundException;
import com.fancy.taxiagent.user.poi.PoiOrderView;
import com.fancy.taxiagent.user.poi.PoiService;
import com.fancy.taxiagent.user.poi.PoiView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POI 接口的鉴权、userId 提取与错误映射验证（真实 JWT 安全链）。
 */
@WebMvcTest(controllers = UserPoiController.class)
@Import({UserPublicApiExceptionHandler.class, UserSecurityConfiguration.class, UserSecurityFailureHandler.class})
class UserPoiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PoiService poiService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("50002", "ADMIN"));
    }

    @Test
    void shouldListOwnPois() throws Exception {
        when(poiService.list(50001L)).thenReturn(List.of(view(1L, "家", "我的家")));

        mockMvc.perform(get("/api/users/poi").header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].poiTag").value("家"));
    }

    @Test
    void shouldGetOwnPoi() throws Exception {
        when(poiService.get(50001L, 1L)).thenReturn(view(1L, "家", "我的家"));

        mockMvc.perform(get("/api/users/poi/1").header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poiName").value("我的家"));
    }

    @Test
    void shouldReturn404WhenPoiMissingOrNotOwned() throws Exception {
        when(poiService.get(50001L, 1L)).thenThrow(new PoiNotFoundException());

        mockMvc.perform(get("/api/users/poi/1").header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
    }

    @Test
    void shouldCreatePoi() throws Exception {
        when(poiService.create(eq(50001L), any(PoiView.class))).thenReturn(view(2L, "公司", "公司名"));

        mockMvc.perform(post("/api/users/poi")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poiTag":"公司","poiName":"公司名","poiAddress":"上海市",
                                 "longitude":121.4737,"latitude":31.2304}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2));

        verify(poiService).create(eq(50001L), any(PoiView.class));
    }

    @Test
    void shouldReturn400ForInvalidPoi() throws Exception {
        when(poiService.create(eq(50001L), any(PoiView.class)))
                .thenThrow(new InvalidPoiException("标签不能为空"));

        mockMvc.perform(post("/api/users/poi")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poiName":"公司名","longitude":121.4737,"latitude":31.2304}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POI"));
    }

    @Test
    void shouldUpdateOwnPoi() throws Exception {
        mockMvc.perform(put("/api/users/poi/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poiTag":"公司","poiName":"新名字","poiAddress":"新地址",
                                 "longitude":121.5,"latitude":31.3}
                                """))
                .andExpect(status().isOk());

        verify(poiService).update(eq(50001L), eq(1L), any(PoiView.class));
    }

    @Test
    void shouldDeleteOwnPoi() throws Exception {
        mockMvc.perform(delete("/api/users/poi/1").header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk());

        verify(poiService).delete(50001L, 1L);
    }

    @Test
    void shouldReturnOrderView() throws Exception {
        when(poiService.orderView(50001L))
                .thenReturn(new PoiOrderView(view(1L, "家", "我的家"), null, List.of()));

        mockMvc.perform(get("/api/users/poi/order").header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.home.poiName").value("我的家"))
                .andExpect(jsonPath("$.work").doesNotExist())
                .andExpect(jsonPath("$.other").isArray());
    }

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/users/poi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn403ForNonUserRole() throws Exception {
        mockMvc.perform(get("/api/users/poi").header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isForbidden());
    }

    private Jwt jwt(String subject, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", role)
                .claim("token_type", "access")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    private PoiView view(Long id, String tag, String name) {
        return new PoiView(id, tag, name, null, new BigDecimal("121.4737"),
                new BigDecimal("31.2304"), LocalDateTime.now());
    }
}
