package com.fancy.taxiagent.agent.tool;

import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    @Test
    void shouldAutoRegisterToolBeans() {
        FakeTool tool = new FakeTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertThat(registry.find("fakeTool")).isSameAs(tool);
        assertThat(registry.names()).containsExactly("fakeTool");
        assertThat(registry.toolCallbacks()).hasSize(1);
        assertThat(registry.toolCallbacks().get(0).getToolDefinition().name())
                .isEqualTo("fakeTool");
    }

    @Test
    void shouldOverwriteDuplicateName() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool(), new FakeTool()));

        assertThat(registry.toolCallbacks()).hasSize(1);
        assertThat(registry.toolCallbacks().get(0).getToolDefinition().name())
                .isEqualTo("fakeTool");
        assertThat(registry.names()).containsExactly("fakeTool");
    }

    private static final class FakeTool implements AgentTool {

        @Override
        public String name() {
            return "fakeTool";
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public Class<?> inputType() {
            return Arguments.class;
        }

        @Override
        public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
            return "ok";
        }

        public record Arguments() {
        }
    }
}
