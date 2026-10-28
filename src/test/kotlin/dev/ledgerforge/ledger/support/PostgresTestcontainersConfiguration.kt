package dev.ledgerforge.ledger.support

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One Postgres 17 database for the whole test JVM. Flyway owns the schema;
 * tests truncate rows between cases because journal tables reject DELETE.
 */
object PostgresTestcontainersConfiguration {
    val container: PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("ledger")
            .withUsername("ledger")
            .withPassword("ledger")
            .apply { start() }
}
