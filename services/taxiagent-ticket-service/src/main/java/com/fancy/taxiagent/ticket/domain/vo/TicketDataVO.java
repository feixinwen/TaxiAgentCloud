package com.fancy.taxiagent.ticket.domain.vo;

public record TicketDataVO(
        Long pendingAssignCount,
        Long processingCount,
        Long todayCreatedCount,
        Long todayCompletedCount
) {
}
