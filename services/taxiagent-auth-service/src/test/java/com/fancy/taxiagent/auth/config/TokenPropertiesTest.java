package com.fancy.taxiagent.auth.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenPropertiesTest {

    @Test
    void shouldBindDefaults() {
        TokenProperties properties = new TokenProperties();

        assertThat(properties.getRefreshTtlSeconds()).isEqualTo(604800);
        assertThat(properties.getFailedLoginThreshold()).isEqualTo(5);
        assertThat(properties.getLockDurationSeconds()).isEqualTo(900);
    }
}
