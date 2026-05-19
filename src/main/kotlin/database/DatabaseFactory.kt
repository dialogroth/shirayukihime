package database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import database.tables.*
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.URI

object DatabaseFactory {

    fun init(application: Application) {

        val databaseUrl = System.getenv("DATABASE_URL")
            ?: error("DATABASE_URL is not set")

        val dbUri = URI(databaseUrl)

        val userInfo = dbUri.userInfo?.split(":") ?: emptyList()
        val user = userInfo.getOrNull(0) ?: ""
        val password = userInfo.getOrNull(1) ?: ""

        val host = dbUri.host
        val port = if (dbUri.port == -1) 5432 else dbUri.port
        val dbName = dbUri.path.removePrefix("/")

        val jdbcUrl = "jdbc:postgresql://$host:$port/$dbName"

        val hikariConfig = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            this.jdbcUrl = jdbcUrl
            username = user
            this.password = password

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

            // Migration: remove seat_order unique constraint if it exists
            exec("DROP INDEX IF EXISTS players_room_id_seat_order_unique")
            exec("ALTER TABLE players DROP CONSTRAINT IF EXISTS players_room_id_seat_order_unique")
            // Try other possible naming conventions
            exec("DROP INDEX IF EXISTS \"players_room_id_seat_order\"")
            exec("ALTER TABLE players DROP CONSTRAINT IF EXISTS \"players_room_id_seat_order\"")
            exec("""
                DO $$
                DECLARE r RECORD;
                BEGIN
                    FOR r IN (
                        SELECT conname FROM pg_constraint
                        WHERE conrelid = 'players'::regclass
                        AND contype = 'u'
                        AND array_length(conkey, 1) = 2
                    ) LOOP
                        EXECUTE 'ALTER TABLE players DROP CONSTRAINT ' || r.conname;
                    END LOOP;
                END $$;
            """.trimIndent())
        }
    }
}