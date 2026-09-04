package com.fancy.taxiagent.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false"
        }
)
class GatewayRouteConfigurationTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    @Test
    void shouldConfigureAuthServiceRoute() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();

        assertThat(routes)
                .isNotNull()
                .anySatisfy(route -> {
                    assertThat(route.getId()).isEqualTo("auth-service");
                    assertThat(route.getUri()).isEqualTo(URI.create("lb://taxiagent-auth-service"));
                    assertThat(route.getPredicates())
                            .anySatisfy(predicate -> {
                                assertThat(predicate.getName()).isEqualTo("Path");
                                assertThat(predicate.getArgs()).containsValue("/api/auth/**");
                            });
                });
    }

    @Test
    void shouldConfigureUserServiceRoute() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();

        assertThat(routes)
                .isNotNull()
                .anySatisfy(route -> {
                    assertThat(route.getId()).isEqualTo("user-service");
                    assertThat(route.getUri()).isEqualTo(URI.create("lb://taxiagent-user-service"));
                    assertThat(route.getPredicates())
                            .anySatisfy(predicate -> {
                                assertThat(predicate.getName()).isEqualTo("Path");
                                assertThat(predicate.getArgs()).containsValue("/api/users/**");
                            });
                });
    }

    @Test
    void shouldConfigureAuthUserManagementRoute() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();

        assertThat(routes)
                .isNotNull()
                .anySatisfy(route -> {
                    assertThat(route.getId()).isEqualTo("auth-user-management");
                    assertThat(route.getUri()).isEqualTo(URI.create("lb://taxiagent-auth-service"));
                    assertThat(route.getPredicates())
                            .anySatisfy(predicate -> {
                                assertThat(predicate.getName()).isEqualTo("Path");
                                // 逗号分隔的路径模式在 PredicateDefinition 中拆分为多个 key
                                assertThat(predicate.getArgs())
                                        .containsValue("/api/users/current/**")
                                        .containsValue("/api/users/admin/**");
                            });
                });
    }

    @Test
    void shouldDeclareAuthUserManagementRouteBeforeGenericUserRoute() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        List<String> ids = routes.stream().map(RouteDefinition::getId).toList();

        assertThat(ids.indexOf("auth-user-management"))
                .isLessThan(ids.indexOf("user-service"));
    }

    @Test
    void shouldConfigureAgentServiceRoute() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();

        assertThat(routes)
                .isNotNull()
                .anySatisfy(route -> {
                    assertThat(route.getId()).isEqualTo("agent-service");
                    assertThat(route.getUri()).isEqualTo(URI.create("lb://taxiagent-agent-service"));
                    assertThat(route.getPredicates())
                            .anySatisfy(predicate -> {
                                assertThat(predicate.getName()).isEqualTo("Path");
                                assertThat(predicate.getArgs()).containsValue("/api/agent/**");
                            });
                });
    }
}
