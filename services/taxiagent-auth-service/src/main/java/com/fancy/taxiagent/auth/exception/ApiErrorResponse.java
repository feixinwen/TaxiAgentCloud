package com.fancy.taxiagent.auth.exception;

import java.time.Instant;

public record ApiErrorResponse(String code, String message, String traceId, Instant timestamp) {}
