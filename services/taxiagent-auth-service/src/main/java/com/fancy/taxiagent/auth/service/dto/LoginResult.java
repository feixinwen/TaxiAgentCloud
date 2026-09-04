package com.fancy.taxiagent.auth.service.dto;

public record LoginResult(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSec,
        long refreshExpiresInSec,
        Long userId,
        String username,
        String role
) {}
