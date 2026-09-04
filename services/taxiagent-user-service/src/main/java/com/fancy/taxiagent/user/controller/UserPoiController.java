package com.fancy.taxiagent.user.controller;

import com.fancy.taxiagent.user.poi.PoiOrderView;
import com.fancy.taxiagent.user.poi.PoiService;
import com.fancy.taxiagent.user.poi.PoiView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户常用地点（POI）接口（仅 USER 角色，userId 取自 JWT subject）。
 */
@RestController
@RequestMapping("/api/users/poi")
public class UserPoiController {

    private final PoiService poiService;

    public UserPoiController(PoiService poiService) {
        this.poiService = poiService;
    }

    @GetMapping
    @PreAuthorize("hasRole('USER')")
    public List<PoiView> list(Authentication authentication) {
        return poiService.list(currentUserId(authentication));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public PoiView get(@PathVariable Long id, Authentication authentication) {
        return poiService.get(currentUserId(authentication), id);
    }

    @GetMapping("/order")
    @PreAuthorize("hasRole('USER')")
    public PoiOrderView order(Authentication authentication) {
        return poiService.orderView(currentUserId(authentication));
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public PoiView create(@RequestBody PoiView input, Authentication authentication) {
        return poiService.create(currentUserId(authentication), input);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public void update(@PathVariable Long id, @RequestBody PoiView input, Authentication authentication) {
        poiService.update(currentUserId(authentication), id, input);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public void delete(@PathVariable Long id, Authentication authentication) {
        poiService.delete(currentUserId(authentication), id);
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalArgumentException("未认证");
        }
        return Long.valueOf(jwt.getSubject());
    }
}
