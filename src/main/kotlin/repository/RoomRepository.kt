package repository

import database.tables.RoomCardSettings
import database.tables.RoomSettings
import database.tables.Rooms
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import model.enums.CardType
import model.enums.RoomStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class RoomRow(
    val id: UUID,
    val roomCode: String,
    val hostPlayerId: UUID?,
    val status: RoomStatus
)

data class CardSettingRow(val cardType: CardType, val count: Int)

data class RoomSettingRow(
    val poisonAppleCount: Int,
    val roles: List<String>,
    val cardSettings: List<CardSettingRow>
)

class RoomRepository {

    fun create(roomCode: String): UUID = transaction {
        Rooms.insertAndGetId {
            it[Rooms.roomCode] = roomCode
            it[status] = RoomStatus.WAITING.name
        }.value
    }

    fun findByCode(code: String): RoomRow? = transaction {
        Rooms.selectAll().where { Rooms.roomCode eq code }.singleOrNull()?.toRoomRow()
    }

    fun findById(id: UUID): RoomRow? = transaction {
        Rooms.selectAll().where { Rooms.id eq id }.singleOrNull()?.toRoomRow()
    }

    fun updateStatus(id: UUID, status: RoomStatus) = transaction {
        Rooms.update({ Rooms.id eq id }) {
            it[Rooms.status] = status.name
        }
    }

    fun updateHost(id: UUID, hostPlayerId: UUID) = transaction {
        Rooms.update({ Rooms.id eq id }) {
            it[Rooms.hostPlayerId] = hostPlayerId
        }
    }

    fun saveSettings(roomId: UUID, poisonAppleCount: Int, roles: List<String>) = transaction {
        RoomSettings.deleteWhere { RoomSettings.roomId eq roomId }
        RoomSettings.insert {
            it[RoomSettings.roomId] = roomId
            it[RoomSettings.poisonAppleCount] = poisonAppleCount
            it[RoomSettings.roles] = Json.encodeToString(roles)
        }
    }

    fun saveCardSettings(roomId: UUID, cardSettings: List<CardSettingRow>) = transaction {
        RoomCardSettings.deleteWhere { RoomCardSettings.roomId eq roomId }
        cardSettings.forEach { setting ->
            RoomCardSettings.insert {
                it[RoomCardSettings.roomId] = roomId
                it[cardType] = setting.cardType.name
                it[count] = setting.count
            }
        }
    }

    fun findSettings(roomId: UUID): RoomSettingRow? = transaction {
        val settings = RoomSettings.selectAll()
            .where { RoomSettings.roomId eq roomId }
            .singleOrNull() ?: return@transaction null

        val cardSettings = RoomCardSettings.selectAll()
            .where { RoomCardSettings.roomId eq roomId }
            .map { CardSettingRow(CardType.valueOf(it[RoomCardSettings.cardType]), it[RoomCardSettings.count]) }

        RoomSettingRow(
            poisonAppleCount = settings[RoomSettings.poisonAppleCount],
            roles = Json.decodeFromString<List<String>>(settings[RoomSettings.roles]),
            cardSettings = cardSettings
        )
    }

    private fun ResultRow.toRoomRow() = RoomRow(
        id = this[Rooms.id].value,
        roomCode = this[Rooms.roomCode],
        hostPlayerId = this[Rooms.hostPlayerId],
        status = RoomStatus.valueOf(this[Rooms.status])
    )
}
