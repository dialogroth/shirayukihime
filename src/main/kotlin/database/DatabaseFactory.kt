package database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import database.tables.Players
import database.tables.RoomCardSettings
import database.tables.RoomSettings
import database.tables.Rooms
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {

    fun init(application: Application) {

        val url = System.getenv("DATABASE_URL")
            ?: application.environment.config.propertyOrNull("database.url")?.getString()
            ?: error("DATABASE_URL is not set")

        val driver = System.getenv("DATABASE_DRIVER")
            ?: "org.postgresql.Driver"

        val user = System.getenv("DATABASE_USER")
            ?: ""

        val password = System.getenv("DATABASE_PASSWORD")
            ?: ""

        val maxPoolSize = System.getenv("DATABASE_POOL_SIZE")
            ?.toIntOrNull()
            ?: 10

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = if (url.startsWith("postgres://")) {
                url.replace("postgres://", "jdbc:postgresql://")
            } else {
                url
            }

            driverClassName = driver
            username = user
            this.password = password

            maximumPoolSize = maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }

        Database.connect(HikariDataSource(hikariConfig))

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Rooms,
                RoomSettings,
                RoomCardSettings,
                Players
            )
        }
    }
}