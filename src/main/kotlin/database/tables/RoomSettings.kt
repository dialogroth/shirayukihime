package database.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

object RoomSettings : UUIDTable("room_settings") {
    val roomId = reference("room_id", Rooms, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val poisonAppleCount = integer("poison_apple_count").default(2)
    val roles = text("roles")  // JSON配列として保存 e.g. ["SNOW_WHITE","QUEEN"]
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
}
