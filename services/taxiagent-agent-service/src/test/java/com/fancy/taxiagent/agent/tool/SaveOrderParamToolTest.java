package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.state.OrderStateService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaveOrderParamToolTest {

    private final OrderStateService stateService = mock(OrderStateService.class);
    private final SaveOrderParamTool tool = new SaveOrderParamTool(stateService, new ObjectMapper());

    @Test
    void shouldSaveValidFieldsAndReturnSavedList() {
        String result = tool.execute("""
                {"vehicleType":1,"isReservation":0,"isExpedited":0,
                 "startAddress":"人民广场","startLat":31.2304,"startLng":121.4737,
                 "endAddress":"虹桥机场","endLat":31.1979,"endLng":121.3363}
                """, context(), null);

        assertThat(result).contains("已保存订单参数").contains("vehicleType=1")
                .contains("startLat=31.2304").contains("endAddress=虹桥机场");
        verify(stateService).setSlot(eq("conv-1"), eq("vehicleType"), eq("1"));
        verify(stateService).setSlot(eq("conv-1"), eq("isReservation"), eq("0"));
        verify(stateService).setSlot(eq("conv-1"), eq("startLat"), eq("31.2304"));
        verify(stateService).setSlot(eq("conv-1"), eq("endAddress"), eq("虹桥机场"));
    }

    @Test
    void shouldSaveScheduledTimeWhenFormattedCorrectly() {
        String future = LocalDateTime.now().plusHours(3)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String result = tool.execute("{\"scheduledTime\":\"" + future + "\"}", context(), null);

        assertThat(result).contains("scheduledTime=" + future);
        verify(stateService).setSlot(eq("conv-1"), eq("scheduledTime"), eq(future));
    }

    @Test
    void shouldRejectPastScheduledTimeWithoutSaving() {
        String result = tool.execute("{\"scheduledTime\":\"2020-01-01 09:00\"}", context(), null);

        assertThat(result).startsWith("Tool execution failed:").contains("必须是未来的时间");
        verify(stateService, never()).setSlot(any(), any(), any());
    }

    @Test
    void shouldRejectScheduledTimeBeyondSevenDaysWithoutSaving() {
        String tooFar = LocalDateTime.now().plusDays(8)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String result = tool.execute("{\"scheduledTime\":\"" + tooFar + "\"}", context(), null);

        assertThat(result).startsWith("Tool execution failed:").contains("7 天");
        verify(stateService, never()).setSlot(any(), any(), any());
    }

    @Test
    void shouldRejectUnknownFieldWithoutSavingAnything() {
        String result = tool.execute("{\"badField\":1}", context(), null);

        assertThat(result).startsWith("Tool execution failed: Unrecognized field");
        verify(stateService, never()).setSlot(any(), any(), any());
    }

    @Test
    void shouldRejectInvalidVehicleType() {
        String result = tool.execute("{\"vehicleType\":5}", context(), null);

        assertThat(result).isEqualTo("Tool execution failed: 车型必须是 1（快车）/2（优享）/3（专车）");
        verify(stateService, never()).setSlot(any(), any(), any());
    }

    @Test
    void shouldRejectOutOfRangeCoordinate() {
        String result = tool.execute("{\"startLat\":200}", context(), null);

        assertThat(result).isEqualTo("Tool execution failed: startLat 超出有效范围");
        verify(stateService, never()).setSlot(any(), any(), any());
    }

    @Test
    void shouldRejectMalformedScheduledTime() {
        String result = tool.execute("{\"scheduledTime\":\"2026/08/27\"}", context(), null);

        assertThat(result).isEqualTo("Tool execution failed: scheduledTime 格式必须为 yyyy-MM-dd HH:mm");
        verify(stateService, never()).setSlot(any(), any(), any());
    }

    @Test
    void shouldRejectEmptyObject() {
        String result = tool.execute("{}", context(), null);

        assertThat(result).isEqualTo("Tool execution failed: 参数必须包含至少一个有效字段");
        verify(stateService, never()).setSlot(any(), any(), any());
    }

    @Test
    void shouldRefuseWhenOrderAlreadyCreated() {
        when(stateService.isOrderCreated("conv-1")).thenReturn(true);

        String result = tool.execute("{\"vehicleType\":1}", context(), null);

        assertThat(result).isEqualTo("订单已创建，如需下新单请创建新对话。");
        verify(stateService, never()).setSlot(any(), any(), any());
    }

    @Test
    void shouldFailOnNullContext() {
        String result = tool.execute("{\"vehicleType\":1}", null, null);

        assertThat(result).isEqualTo("Tool execution failed: 缺少执行上下文");
    }

    @Test
    void shouldFailOnMalformedJson() {
        String result = tool.execute("not-json", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
        verify(stateService, never()).setSlot(any(), any(), any());
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                90001L, "conv-1", "run-1", "Bearer t", "trace-1", "帮我叫车",
                List.of());
    }
}
