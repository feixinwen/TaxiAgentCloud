package com.fancy.taxiagent.agent.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.dto.RagSearchRequest;
import com.fancy.taxiagent.agent.client.dto.RagSearchResult;
import com.fancy.taxiagent.agent.config.AgentFeignConfiguration;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.Retryer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 RAG 客户端的跨服务契约、输入边界和稳定错误映射。
 */
class RagServiceClientTest {

    private static final String AUTHORIZATION = "Bearer secret-access-token";
    private static final String TRACE_ID = "trace-123";
    private static final int MAX_RESULT_BYTES = 16 * 1024;

    @Test
    void shouldForwardExplicitHeadersAndNormalizedRequest() {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        RagSearchResult expected = new RagSearchResult("group-1", "trimmed query", "safe answer");
        when(feignClient.search(AUTHORIZATION, TRACE_ID, new RagSearchRequest("trimmed query", 5)))
                .thenReturn(List.of(expected));

        List<RagSearchResult> actual = client.searchKnowledgeBase(
                AUTHORIZATION, TRACE_ID, "  trimmed query  ", null);

        assertThat(actual).containsExactly(expected);
        verify(feignClient).search(AUTHORIZATION, TRACE_ID, new RagSearchRequest("trimmed query", 5));
    }

    @Test
    void shouldCapTopKAtFive() {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        when(feignClient.search(any(), any(), any())).thenReturn(List.of());

        client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "question", 6);

        verify(feignClient).search(AUTHORIZATION, TRACE_ID, new RagSearchRequest("question", 5));
    }

    @Test
    void shouldRejectBlankOrTooLongQueryWithoutCallingRag() {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);

        assertThatIllegalArgumentException().isThrownBy(
                () -> client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "   ", 1));
        assertThatIllegalArgumentException().isThrownBy(
                () -> client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "x".repeat(501), 1));

        verify(feignClient, never()).search(any(), any(), any());
    }

    @Test
    void shouldAcceptOneToFiveHundredCharacterQueryBoundaries() {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        when(feignClient.search(any(), any(), any())).thenReturn(List.of());

        client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "x", 1);
        client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "x".repeat(500), 1);

        verify(feignClient).search(AUTHORIZATION, TRACE_ID, new RagSearchRequest("x", 1));
        verify(feignClient).search(AUTHORIZATION, TRACE_ID, new RagSearchRequest("x".repeat(500), 1));
    }

    @Test
    void shouldUseRagJsonFieldNames() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        JsonNode request = objectMapper.readTree(objectMapper.writeValueAsString(new RagSearchRequest("question", 3)));
        JsonNode result = objectMapper.readTree(objectMapper.writeValueAsString(
                new RagSearchResult("group-1", "question", "answer")));

        assertThat(request.fieldNames()).toIterable().containsExactlyInAnyOrder("question", "topK");
        assertThat(result.fieldNames()).toIterable().containsExactlyInAnyOrder("groupId", "question", "answer");
    }

    @Test
    void shouldExposeFixedRagFeignContract() throws Exception {
        FeignClient feignClient = RagServiceFeignClient.class.getAnnotation(FeignClient.class);
        PostMapping postMapping = RagServiceFeignClient.class.getMethod(
                "search", String.class, String.class, RagSearchRequest.class).getAnnotation(PostMapping.class);
        java.lang.reflect.Parameter[] parameters = RagServiceFeignClient.class.getMethod(
                "search", String.class, String.class, RagSearchRequest.class).getParameters();

        assertThat(feignClient.name()).isEqualTo("taxiagent-rag-service");
        assertThat(feignClient.path()).isEqualTo("/api/rag");
        assertThat(postMapping.value()).containsExactly("/search");
        assertThat(parameters[0].getAnnotation(RequestHeader.class).value()).isEqualTo("Authorization");
        assertThat(parameters[1].getAnnotation(RequestHeader.class).value()).isEqualTo("X-Trace-Id");
    }

    @Test
    void shouldMapConnectAndTimeoutFailuresToRagUnavailable() {
        assertRagUnavailable(new RetryableException(
                0, "connect failed", Request.HttpMethod.POST, new ConnectException("refused"), (Long) null,
                request()));
        assertRagUnavailable(new RetryableException(
                0, "read timed out", Request.HttpMethod.POST,
                new java.net.SocketTimeoutException("timed out"), (Long) null, request()));
    }

    @Test
    void shouldMapFourHundredResponsesToRagRequestRejected() {
        assertMappedCode(FeignException.errorStatus("search", response(400)), "RAG_REQUEST_REJECTED");
        assertMappedCode(FeignException.errorStatus("search", response(499)), "RAG_REQUEST_REJECTED");
    }

    @Test
    void shouldMapFiveHundredResponsesToRagUnavailable() {
        assertRagUnavailable(FeignException.errorStatus("search", response(500)));
        assertRagUnavailable(FeignException.errorStatus("search", response(599)));
    }

    @Test
    void shouldTruncateResultsAtWholeEntriesWithinSixteenKiB() throws Exception {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        RagSearchResult first = new RagSearchResult("g1", "q1", "a".repeat(8 * 1024));
        RagSearchResult second = new RagSearchResult("g2", "q2", "b".repeat(8 * 1024));
        when(feignClient.search(any(), any(), any())).thenReturn(List.of(first, second));

        List<RagSearchResult> actual = client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "question", 2);

        assertThat(actual).containsExactly(first);
        assertThat(serializedSize(actual)).isLessThanOrEqualTo(MAX_RESULT_BYTES);
    }

    @Test
    void shouldRetainAResultWhoseSerializedArrayExactlyMatchesSixteenKiB() throws Exception {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        RagSearchResult emptyAnswer = new RagSearchResult("g", "q", "");
        RagSearchResult exactBoundary = new RagSearchResult(
                "g", "q", "a".repeat(MAX_RESULT_BYTES - serializedSize(List.of(emptyAnswer))));
        when(feignClient.search(any(), any(), any())).thenReturn(List.of(exactBoundary));

        List<RagSearchResult> actual = client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "question", 1);

        assertThat(actual).containsExactly(exactBoundary);
        assertThat(serializedSize(actual)).isEqualTo(MAX_RESULT_BYTES);
    }

    @Test
    void shouldDiscardAResultWhoseSerializedArrayExceedsSixteenKiB() throws Exception {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        RagSearchResult emptyAnswer = new RagSearchResult("g", "q", "");
        RagSearchResult beyondBoundary = new RagSearchResult(
                "g", "q", "a".repeat(MAX_RESULT_BYTES - serializedSize(List.of(emptyAnswer)) + 1));
        when(feignClient.search(any(), any(), any())).thenReturn(List.of(beyondBoundary));

        List<RagSearchResult> actual = client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "question", 1);

        assertThat(serializedSize(List.of(beyondBoundary))).isGreaterThan(MAX_RESULT_BYTES);
        assertThat(actual).isEmpty();
    }

    @Test
    void shouldCountMultibyteAndEscapedCharactersInSerializedBudget() throws Exception {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        RagSearchResult emptyAnswer = new RagSearchResult("g", "q", "");
        int rawAnswerBytes = MAX_RESULT_BYTES - rawFieldBytes(emptyAnswer);
        RagSearchResult escapingResult = new RagSearchResult(
                "g", "q", "汉" + "\"".repeat(rawAnswerBytes - 3));
        when(feignClient.search(any(), any(), any())).thenReturn(List.of(escapingResult));

        List<RagSearchResult> actual = client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "question", 1);

        assertThat(rawFieldBytes(escapingResult)).isEqualTo(MAX_RESULT_BYTES);
        assertThat(serializedSize(List.of(escapingResult))).isGreaterThan(MAX_RESULT_BYTES);
        assertThat(actual).isEmpty();
    }

    @Test
    void shouldSkipNullResultsInsteadOfFailingDuringTruncation() {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        RagSearchResult first = new RagSearchResult("g1", "q1", "a1");
        RagSearchResult second = new RagSearchResult("g2", "q2", "a2");
        when(feignClient.search(any(), any(), any())).thenReturn(java.util.Arrays.asList(first, null, second));

        List<RagSearchResult> actual = client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "question", 2);

        assertThat(actual).containsExactly(first, second);
    }

    @Test
    void shouldWriteSafeSuccessCallLogWithoutRagContent() {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        String query = "sensitive user query";
        String answer = "sensitive RAG answer";
        when(feignClient.search(any(), any(), any()))
                .thenReturn(List.of(new RagSearchResult("group-1", query, answer)));
        Logger logger = (Logger) LoggerFactory.getLogger(RagServiceClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, query, 1);
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + right);
        assertThat(logs).containsPattern(
                "event=rag_remote_call target=taxiagent-rag-service traceId=trace-123 status=success durationMs=\\d+");
        assertThat(logs).doesNotContain(query, AUTHORIZATION, answer);
    }

    @Test
    void shouldWriteSafeFailureCallLogWithoutExceptionDetails() {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        String query = "sensitive user query";
        String failureDetail = "answer and token must not be logged";
        when(feignClient.search(any(), any(), any())).thenThrow(new RetryableException(
                0, failureDetail, Request.HttpMethod.POST, new ConnectException(failureDetail), (Long) null, request()));
        Logger logger = (Logger) LoggerFactory.getLogger(RagServiceClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, query, 1))
                    .isInstanceOf(AgentExecutionException.class);
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + right);
        assertThat(logs).containsPattern(
                "event=rag_remote_call target=taxiagent-rag-service traceId=trace-123 status=failure durationMs=\\d+");
        assertThat(logs).doesNotContain(query, AUTHORIZATION, failureDetail);
    }

    @Test
    void shouldConfigureThreeSecondTimeoutsAndDisableRetries() {
        AgentFeignConfiguration configuration = new AgentFeignConfiguration();

        Request.Options options = configuration.ragFeignRequestOptions();
        Retryer retryer = configuration.ragFeignRetryer();
        feign.Logger.Level loggerLevel = configuration.ragFeignLoggerLevel();

        assertThat(options.connectTimeout()).isEqualTo(3_000);
        assertThat(options.connectTimeoutUnit()).isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(options.readTimeout()).isEqualTo(3_000);
        assertThat(options.readTimeoutUnit()).isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(retryer).isSameAs(Retryer.NEVER_RETRY);
        assertThat(loggerLevel).isEqualTo(feign.Logger.Level.NONE);
    }

    private void assertRagUnavailable(RuntimeException failure) {
        assertMappedCode(failure, "RAG_UNAVAILABLE");
    }

    private void assertMappedCode(RuntimeException failure, String expectedCode) {
        RagServiceFeignClient feignClient = mock(RagServiceFeignClient.class);
        RagServiceClient client = new RagServiceClient(feignClient);
        when(feignClient.search(eq(AUTHORIZATION), eq(TRACE_ID), any())).thenThrow(failure);

        assertThatThrownBy(() -> client.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "question", 1))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(exception -> ((AgentExecutionException) exception).getCode())
                .isEqualTo(expectedCode);
    }

    private Request request() {
        return Request.create(Request.HttpMethod.POST, "http://taxiagent-rag-service/api/rag/search",
                java.util.Map.of(), new byte[0], StandardCharsets.UTF_8);
    }

    private Response response(int status) {
        return Response.builder()
                .status(status)
                .reason("failure")
                .request(request())
                .body("", StandardCharsets.UTF_8)
                .build();
    }

    private int serializedSize(List<RagSearchResult> results) throws Exception {
        return new ObjectMapper().writeValueAsBytes(results).length;
    }

    private int rawFieldBytes(RagSearchResult result) {
        return (result.groupId() + result.question() + result.answer()).getBytes(StandardCharsets.UTF_8).length;
    }
}
