package com.fancy.taxiagent.auth.client.user;

import com.fancy.taxiagent.auth.exception.AuthApiException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Auth 的 User Service 防腐门面正确委托调用并保留远程异常。
 */
class UserProfileRemoteServiceTest {

    @Test
    void shouldDelegateIdempotentProfileCreation() {
        UserServiceClient client = mock(UserServiceClient.class);
        UserProfileRemoteService remoteService = new UserProfileRemoteService(client);
        UserProfileClientResponse expected = profile();
        when(client.createProfile(10001L, new CreateUserProfileClientRequest("alice", "USER")))
                .thenReturn(expected);

        UserProfileClientResponse actual = remoteService.createProfile(10001L, "alice", "USER");

        assertThat(actual).isEqualTo(expected);
        verify(client).createProfile(10001L, new CreateUserProfileClientRequest("alice", "USER"));
    }

    @Test
    void shouldMapUserConflictToUsernameConflictException() {
        UserServiceClient client = mock(UserServiceClient.class);
        UserProfileRemoteService remoteService = new UserProfileRemoteService(client);
        Response conflictResponse = Response.builder()
                .status(409)
                .reason("Conflict")
                .request(Request.create(Request.HttpMethod.PUT,
                        "http://taxiagent-user-service/internal/users/10001/profile",
                        java.util.Map.of(),
                        new byte[0],
                        StandardCharsets.UTF_8))
                .body("", StandardCharsets.UTF_8)
                .build();
        when(client.createProfile(10001L, new CreateUserProfileClientRequest("alice", "USER")))
                .thenThrow(FeignException.errorStatus("createProfile", conflictResponse));

        assertThatThrownBy(() -> remoteService.createProfile(10001L, "alice", "USER"))
                .isInstanceOf(AuthApiException.class)
                .extracting(e -> ((AuthApiException) e).getCode())
                .isEqualTo("USERNAME_CONFLICT");
    }

    @Test
    void shouldMapProfileNotFoundToUserProfileNotFoundException() {
        UserServiceClient client = mock(UserServiceClient.class);
        UserProfileRemoteService remoteService = new UserProfileRemoteService(client);
        Response notFoundResponse = Response.builder()
                .status(404)
                .reason("Not Found")
                .request(Request.create(Request.HttpMethod.GET,
                        "http://taxiagent-user-service/internal/users/resolve?username=alice&role=USER",
                        java.util.Map.of(),
                        new byte[0],
                        StandardCharsets.UTF_8))
                .body("", StandardCharsets.UTF_8)
                .build();
        when(client.resolveProfile("alice", "USER"))
                .thenThrow(FeignException.errorStatus("resolveProfile", notFoundResponse));

        assertThatThrownBy(() -> remoteService.resolveProfile("alice", "USER"))
                .isInstanceOf(AuthApiException.class)
                .extracting(e -> ((AuthApiException) e).getCode())
                .isEqualTo("USER_PROFILE_NOT_FOUND");
    }

    @Test
    void shouldPropagateRemoteFailureForOrchestratorToHandle() {
        UserServiceClient client = mock(UserServiceClient.class);
        UserProfileRemoteService remoteService = new UserProfileRemoteService(client);
        IllegalStateException failure = new IllegalStateException("remote unavailable");
        when(client.getProfile(10002L)).thenThrow(failure);

        assertThatThrownBy(() -> remoteService.getProfile(10002L)).isSameAs(failure);
    }

    @Test
    void shouldDelegatePaging() {
        UserServiceClient client = mock(UserServiceClient.class);
        UserProfileRemoteService remoteService = new UserProfileRemoteService(client);
        UserProfilePageClientResponse expected = new UserProfilePageClientResponse(1, List.of(profile()));
        when(client.pageUsers("al", "USER", null, 1, 10)).thenReturn(expected);

        UserProfilePageClientResponse actual = remoteService.pageUsers("al", "USER", null, 1, 10);

        assertThat(actual.total()).isEqualTo(1);
        assertThat(actual.records().get(0).username()).isEqualTo("alice");
        verify(client).pageUsers("al", "USER", null, 1, 10);
    }

    @Test
    void shouldMapUsernameConflictOnUpdate() {
        UserServiceClient client = mock(UserServiceClient.class);
        UserProfileRemoteService remoteService = new UserProfileRemoteService(client);
        Response conflict = Response.builder()
                .status(409)
                .reason("Conflict")
                .request(Request.create(Request.HttpMethod.PATCH,
                        "http://taxiagent-user-service/internal/users/10001/username",
                        java.util.Map.of(),
                        new byte[0],
                        StandardCharsets.UTF_8))
                .body("", StandardCharsets.UTF_8)
                .build();
        when(client.updateUsername(10001L, new UsernameUpdateClientRequest("taken")))
                .thenThrow(FeignException.errorStatus("updateUsername", conflict));

        assertThatThrownBy(() -> remoteService.updateUsername(10001L, "taken"))
                .isInstanceOf(AuthApiException.class)
                .extracting(e -> ((AuthApiException) e).getCode())
                .isEqualTo("USERNAME_CONFLICT");
    }

    @Test
    void shouldDelegateRoleUpdateAndBatchStatus() {
        UserServiceClient client = mock(UserServiceClient.class);
        UserProfileRemoteService remoteService = new UserProfileRemoteService(client);
        when(client.updateRole(10001L, new RoleUpdateClientRequest("SUPPORT")))
                .thenReturn(profile());
        when(client.batchStatus(new BatchStatusClientRequest(List.of(10001L), "DISABLE")))
                .thenReturn(new BatchStatusClientResponse(1));

        UserProfileClientResponse updated = remoteService.updateRole(10001L, "SUPPORT");
        int affected = remoteService.batchStatus(List.of(10001L), "DISABLE");

        assertThat(updated.role()).isEqualTo("USER");
        assertThat(affected).isEqualTo(1);
        verify(client).updateRole(10001L, new RoleUpdateClientRequest("SUPPORT"));
        verify(client).batchStatus(new BatchStatusClientRequest(List.of(10001L), "DISABLE"));
    }

    private UserProfileClientResponse profile() {
        return new UserProfileClientResponse(
                10001L,
                "alice",
                "USER",
                1,
                LocalDateTime.of(2026, 8, 7, 17, 0),
                LocalDateTime.of(2026, 8, 7, 17, 0)
        );
    }
}
