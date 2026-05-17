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
        val config = application.environment.config
        val url = config.property("database.url").getString()
        val driver = config.property("database.driver").getString()
        val user = config.property("database.user").getString()
        val password = config.property("database.password").getString()
        val maxPoolSize = config.property("database.maxPoolSize").getString().toInt()

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = url
            driverClassName = driver
            username = user
            this.password = password
            maximumPoolSize = maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
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
