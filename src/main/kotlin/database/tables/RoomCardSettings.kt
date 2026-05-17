package database.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

object RoomCardSettings : UUIDTable("room_card_settings") {
    val roomId = reference("room_id", Rooms, onDelete = ReferenceOption.CASCADE)
    val cardType = varchar("card_type", 30)
    val count = integer("count")
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(roomId, cardType)
    }
}
