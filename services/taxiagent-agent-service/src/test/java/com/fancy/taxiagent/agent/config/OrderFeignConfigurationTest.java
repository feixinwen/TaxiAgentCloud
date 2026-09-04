package com.fancy.taxiagent.agent.config;

import feign.Request;
import feign.Retryer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderFeignConfigurationTest {

    private final OrderFeignConfiguration configuration = new OrderFeignConfiguration();

    @Test
    void shouldAllowReadTimeoutLongerThanOrderCreationDuration() {
        Request.Options options = configuration.orderFeignRequestOptions();
        assertThat(options.readTimeoutMillis())
                .as("下单同步规划路线+写轨迹实测约 3 秒，读超时必须显著大于该值")
                .isGreaterThan(5_000);
        assertThat(options.connectTimeoutMillis()).isEqualTo(3_000);
    }

    @Test
    void shouldNeverRetryOrderCalls() {
        assertThat(configuration.orderFeignRetryer()).isSameAs(Retryer.NEVER_RETRY);
    }
}
