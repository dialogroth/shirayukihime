package database.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

object Rooms : UUIDTable("rooms") {
    val roomCode = varchar("room_code", 6).uniqueIndex()
    val hostPlayerId = uuid("host_player_id").nullable()
    val status = varchar("status", 20).default("WAITING")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
}
