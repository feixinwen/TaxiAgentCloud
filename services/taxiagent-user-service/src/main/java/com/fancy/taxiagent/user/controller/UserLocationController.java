package com.fancy.taxiagent.user.controller;

import com.fancy.taxiagent.user.location.UserLocationService;
import com.fancy.taxiagent.user.location.UserLocationView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户当前位置接口（仅 USER 角色）。
 */
@RestController
@RequestMapping("/api/users/loc")
public class UserLocationController {

    private final UserLocationService userLocationService;

    public UserLocationController(UserLocationService userLocationService) {
        this.userLocationService = userLocationService;
    }

    @GetMapping
    @PreAuthorize("hasRole('USER')")
    public UserLocationView get(Authentication authentication) {
        return userLocationService.get(currentUserId(authentication));
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public void save(@RequestBody UserLocationView location, Authentication authentication) {
        userLocationService.save(currentUserId(authentication), location);
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalArgumentException("未认证");
        }
        return Long.valueOf(jwt.getSubject());
    }
}
