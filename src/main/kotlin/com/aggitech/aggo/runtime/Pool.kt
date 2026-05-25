package com.aggitech.aggo.runtime

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.dialect.SqlDialect
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ValidationDepth
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import java.time.Duration

/**
 * Configuration for [AggoPool]. Mirrors the most useful r2dbc-pool knobs without
 * forcing the consumer to depend on r2dbc-pool directly.
 */
data class PoolConfig(
    val initialSize: Int = 2,
    val maxSize: Int = 10,
    val maxIdleTime: Duration = Duration.ofMinutes(5),
    val maxAcquireTime: Duration = Duration.ofSeconds(5),
    val validationQuery: String = "SELECT 1",
    /**
     * P-4: prepared-statement cache size per pooled connection. Driver-side
     * statement reuse skips the parse + plan stage on hot queries. Passed
     * through `preparedStatementCacheQueries` r2dbc-postgresql option.
     * Set to 0 to disable; default matches the driver's default.
     */
    val preparedStatementCacheQueries: Int = 256,
)

data class PostgresConfig(
    val host: String,
    val port: Int = 5432,
    val database: String,
    val user: String,
    val password: String,
    val sslMode: String? = null,
    val pool: PoolConfig = PoolConfig(),
) {
    init {
        // Strict: host/database/user must be plain identifiers/hostnames.
        // This blocks the JDBC/R2DBC URL-injection vector documented in S-2.
        require(host.matches(SAFE_HOST)) { "invalid host: '$host'" }
        require(database.matches(SAFE_NAME)) { "invalid database name: '$database'" }
        require(user.matches(SAFE_USER)) { "invalid user: '$user'" }
        require(port in 1..65535) { "invalid port: $port" }
    }

    private companion object {
        // Hostname (RFC 1123) or IPv4/IPv6 literal. We forbid '?', '&', '=' explicitly.
        val SAFE_HOST = Regex("^[A-Za-z0-9._:\\-\\[\\]]+$")
        val SAFE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        val SAFE_USER = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]*$")
    }
}

/**
 * Production-grade pool wrapper. Delegates to `io.r2dbc.pool.ConnectionPool`
 * (resolves connection-leak, race, validation, and recursive-acquire bugs
 * from the original AggORM). [acquire] / [release] are exposed so [Session]
 * can hold a connection across multiple statements (the transactional fix).
 */
class AggoPool internal constructor(
    private val delegate: ConnectionPool,
    val dialect: SqlDialect,
) : AutoCloseable {

    suspend fun acquire(): Connection = delegate.create().awaitSingle()

    suspend fun release(connection: Connection) {
        // r2dbc-pool returns a proxy whose close() releases instead of physically closing.
        connection.close().awaitFirstOrNull()
    }

    override fun close() {
        delegate.dispose()
    }

    companion object {
        fun postgres(config: PostgresConfig): AggoPool {
            val optionsBuilder = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, config.host)
                .option(ConnectionFactoryOptions.PORT, config.port)
                .option(ConnectionFactoryOptions.DATABASE, config.database)
                .option(ConnectionFactoryOptions.USER, config.user)
                .option(ConnectionFactoryOptions.PASSWORD, config.password)

            config.sslMode?.let { mode ->
                optionsBuilder.option(io.r2dbc.spi.Option.valueOf("sslMode"), mode)
            }

            // P-4: driver-side prepared-statement cache.
            optionsBuilder.option(
                io.r2dbc.spi.Option.valueOf<Int>("preparedStatementCacheQueries"),
                config.pool.preparedStatementCacheQueries,
            )

            val factory = ConnectionFactories.get(optionsBuilder.build())

            val poolConfig = ConnectionPoolConfiguration.builder(factory)
                .initialSize(config.pool.initialSize)
                .maxSize(config.pool.maxSize)
                .maxIdleTime(config.pool.maxIdleTime)
                .maxAcquireTime(config.pool.maxAcquireTime)
                .validationQuery(config.pool.validationQuery)
                .validationDepth(ValidationDepth.REMOTE)
                .build()

            return AggoPool(ConnectionPool(poolConfig), PostgresDialect)
        }
    }
}
