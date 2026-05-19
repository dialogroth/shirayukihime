package database.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

object Players : UUIDTable("players") {
    val roomId = reference("room_id", Rooms, onDelete = ReferenceOption.CASCADE)
    val userName = varchar("user_name", 12)
    val seatOrder = integer("seat_order")
    val isHost = bool("is_host").default(false)
    val isConnected = bool("is_connected").default(true)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(roomId, userName)
    }
}
