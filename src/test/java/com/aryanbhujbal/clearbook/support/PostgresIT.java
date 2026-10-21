package com.aryanbhujbal.clearbook.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for tests that need a real PostgreSQL.
 *
 * <p>A real one, not H2 and not a mock, because the properties under test are
 * properties of PostgreSQL specifically: the deferred constraint trigger that
 * enforces the zero-sum rule at commit, the UNIQUE index that makes
 * idempotency a guarantee rather than a hope, and the optimistic-lock
 * collision that two concurrent transactions actually produce. H2 does not
 * implement the first, and a mock implements none of them — a suite built on
 * either would pass while the production database rejected every posting.
 *
 * <p>The container is {@code static}, so one database serves every test class
 * that extends this. Starting a fresh PostgreSQL per class turns a thirty
 * second suite into a five minute one, and Testcontainers' reuse of a shared
 * static container is the standard answer.
 *
 * <p>Tagged {@code docker}: {@code mvn test} skips these, {@code mvn verify}
 * runs them. Without that split, nobody without a Docker daemon can run the
 * unit tests at all.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
public abstract class PostgresIT {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("clearbook")
                    .withUsername("clearbook")
                    .withPassword("clearbook");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway builds the schema; Hibernate only checks the entities match.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }
}
