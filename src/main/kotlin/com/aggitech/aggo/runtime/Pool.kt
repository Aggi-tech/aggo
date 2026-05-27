package com.aggitech.aggo.runtime

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.dialect.SqlDialect
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ValidationDepth
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import java.time.Duration

/**
 * Connection pool tuning options. Passed as `pool = PoolConfig(…)` inside [PostgresConfig].
 *
 * The defaults suit most microservices; adjust only when you have measured a bottleneck.
 *
 * ```kotlin
 * PostgresConfig(
 *     host = "db.internal",
 *     database = "mydb",
 *     user = "app",
 *     password = secret,
 *     pool = PoolConfig(
 *         initialSize = 5,
 *         maxSize     = 20,
 *         maxIdleTime = Duration.ofMinutes(10),
 *     )
 * )
 * ```
 */
data class PoolConfig(
    /** Connections opened eagerly at startup. */
    val initialSize: Int = 2,
    /** Maximum simultaneous connections. Requests that exceed this block until [maxAcquireTime]. */
    val maxSize: Int = 10,
    /** How long an idle connection stays in the pool before being closed. */
    val maxIdleTime: Duration = Duration.ofMinutes(5),
    /** Maximum time to wait for an available connection before throwing. */
    val maxAcquireTime: Duration = Duration.ofSeconds(5),
    /** SQL statement used to validate connections before lending them. */
    val validationQuery: String = "SELECT 1",
    /**
     * Number of prepared statements cached per pooled connection.
     * Driver-level caching skips the parse+plan stage on repeated queries.
     * Set to `0` to disable the cache entirely.
     */
    val preparedStatementCacheQueries: Int = 256,
)

/**
 * Connection parameters for a PostgreSQL database.
 *
 * All string fields are validated against strict allowlists at construction time
 * to prevent URL-injection attacks via malicious host/database/user values.
 *
 * ```kotlin
 * val config = PostgresConfig(
 *     host     = "localhost",
 *     port     = 5432,
 *     database = "myapp",
 *     user     = "appuser",
 *     password = System.getenv("DB_PASSWORD"),
 *     sslMode  = "require",  // or "disable", "verify-full", etc.
 *     pool     = PoolConfig(maxSize = 20),
 * )
 * val aggo = Aggo(AggoPool.postgres(config))
 * ```
 */
data class PostgresConfig(
    val host: String,
    val port: Int = 5432,
    val database: String,
    val user: String,
    val password: String,
    /** R2DBC SSL mode: `"disable"`, `"allow"`, `"prefer"`, `"require"`, `"verify-ca"`, `"verify-full"`. */
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
 * Managed connection pool used by [Aggo]. Create it via [AggoPool.Companion.postgres].
 *
 * Do not use [acquire] / [release] directly — those are internal hooks for [Aggo.read]
 * and [Aggo.tx]. Interact with the database exclusively through the [Aggo] API.
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
        /**
         * Creates an [AggoPool] from a caller-provided R2DBC [ConnectionFactory]
         * and SQL dialect. Use this for non-PostgreSQL drivers or custom pool
         * wiring while keeping Aggo execution on the dialect boundary.
         */
        fun r2dbc(
            factory: ConnectionFactory,
            dialect: SqlDialect,
            pool: PoolConfig = PoolConfig(),
        ): AggoPool {
            val poolConfig = ConnectionPoolConfiguration.builder(factory)
                .initialSize(pool.initialSize)
                .maxSize(pool.maxSize)
                .maxIdleTime(pool.maxIdleTime)
                .maxAcquireTime(pool.maxAcquireTime)
                .validationQuery(pool.validationQuery)
                .validationDepth(ValidationDepth.REMOTE)
                .build()

            return AggoPool(ConnectionPool(poolConfig), dialect)
        }

        /**
         * Creates an [AggoPool] connected to a PostgreSQL database.
         *
         * ```kotlin
         * val pool = AggoPool.postgres(
         *     PostgresConfig(host = "localhost", database = "mydb", user = "app", password = secret)
         * )
         * val aggo = Aggo(pool)
         * ```
         */
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

            return r2dbc(ConnectionFactories.get(optionsBuilder.build()), PostgresDialect, config.pool)
        }
    }
}
