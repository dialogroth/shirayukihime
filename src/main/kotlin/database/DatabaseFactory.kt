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

        val rawUrl = System.getenv("DATABASE_URL")
            ?: error("DATABASE_URL is not set")

        val jdbcUrl = convertToJdbcUrl(rawUrl)

        val hikariConfig = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            this.jdbcUrl = jdbcUrl

            // Renderは基本これだけでOK
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"

            validate()
        }

        val dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Rooms,
                RoomSettings,
                RoomCardSettings,
                Players
            )
        }
    }

    private fun convertToJdbcUrl(url: String): String {
        return when {
            url.startsWith("postgres://") ->
                url.replace("postgres://", "jdbc:postgresql://")

            url.startsWith("postgresql://") ->
                url.replace("postgresql://", "jdbc:postgresql://")

            url.startsWith("jdbc:postgresql://") ->
                url

            else ->
                error("Invalid DATABASE_URL format: $url")
        }
    }
}