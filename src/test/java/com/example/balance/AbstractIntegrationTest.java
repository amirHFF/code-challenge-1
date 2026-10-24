package com.example.balance;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Integration tests need Docker (Testcontainers) and are skipped when it is not available. */
@SpringBootTest
@EnabledIf(value = "com.example.balance.Infra#dockerAvailable", disabledReason = "Docker is required")
abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        Infra.start();
        r.add("spring.datasource.url", Infra::jdbcUrl);
        r.add("spring.datasource.username", Infra::dbUser);
        r.add("spring.datasource.password", Infra::dbPassword);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
        r.add("spring.data.redis.host", Infra::redisHost);
        r.add("spring.data.redis.password", Infra::redisPass);
        r.add("spring.data.redis.port", () -> String.valueOf(Infra.redisPort()));
    }
}
