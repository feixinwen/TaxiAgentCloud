package com.fancy.taxiagent.agent.config;

import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.model.AgentModelGateway;
import com.fancy.taxiagent.agent.model.ModelRequest;
import com.fancy.taxiagent.agent.model.ModelTurn;
import com.fancy.taxiagent.agent.model.SpringAiAgentModelGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentModelConfigurationTest {

    private final AgentModelConfiguration configuration = new AgentModelConfiguration();

    @Test
    void shouldUseSpringAiGatewayWhenApiKeyIsConfigured() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setApiKey("configured-test-key");
        properties.setBaseUrl("https://provider.invalid");

        assertThat(configuration.agentModelGateway(properties, new com.fancy.taxiagent.agent.tool.ToolRegistry(java.util.List.of())))
                .isInstanceOf(SpringAiAgentModelGateway.class);
    }

    @Test
    void shouldUseStableUnavailableGatewayWhenApiKeyIsBlank() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setApiKey("  ");
        AgentModelGateway gateway = configuration.agentModelGateway(properties, new com.fancy.taxiagent.agent.tool.ToolRegistry(java.util.List.of()));
        ModelRequest request = new ModelRequest(
                "system",
                "support-v1",
                List.of(ModelTurn.user("question"))
        );

        assertThatThrownBy(() -> gateway.stream(
                request, java.util.Set.of(), ignored -> { }, Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(AgentExecutionException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("MODEL_UNAVAILABLE"));
    }
}
