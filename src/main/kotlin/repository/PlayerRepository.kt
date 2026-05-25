package repository

import database.tables.Players
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class PlayerRow(
    val id: UUID,
    val roomId: UUID,
    val userName: String,
    val seatOrder: Int,
    val isHost: Boolean,
    val isConnected: Boolean
)

class PlayerRepository {

    fun create(roomId: UUID, userName: String, seatOrder: Int, isHost: Boolean = false): UUID = transaction {
        Players.insertAndGetId {
            it[Players.roomId] = roomId
            it[Players.userName] = userName
            it[Players.seatOrder] = seatOrder
            it[Players.isHost] = isHost
        }.value
    }

    fun findById(id: UUID): PlayerRow? = transaction {
        Players.selectAll().where { Players.id eq id }.singleOrNull()?.toPlayerRow()
    }

    fun findByRoomId(roomId: UUID): List<PlayerRow> = transaction {
        Players.selectAll()
            .where { Players.roomId eq roomId }
            .orderBy(Players.seatOrder to SortOrder.ASC)
            .map { it.toPlayerRow() }
    }

    fun existsUserNameInRoom(roomId: UUID, userName: String): Boolean = transaction {
        Players.selectAll()
            .where { (Players.roomId eq roomId) and (Players.userName eq userName) }
            .count() > 0
    }

    fun countByRoomId(roomId: UUID): Int = transaction {
        Players.selectAll().where { Players.roomId eq roomId }.count().toInt()
    }

    fun updateConnected(id: UUID, isConnected: Boolean) = transaction {
        Players.update({ Players.id eq id }) {
            it[Players.isConnected] = isConnected
        }
    }

    fun updateIsHost(id: UUID, isHost: Boolean) = transaction {
        Players.update({ Players.id eq id }) {
            it[Players.isHost] = isHost
        }
    }

    fun updateSeatOrder(id: UUID, newSeatOrder: Int) = transaction {
        Players.update({ Players.id eq id }) {
            it[Players.seatOrder] = newSeatOrder
        }
    }

    fun swapSeatOrders(idA: UUID, idB: UUID) = transaction {
        val playerA = Players.selectAll().where { Players.id eq idA }.single()
        val playerB = Players.selectAll().where { Players.id eq idB }.single()
        val orderA = playerA[Players.seatOrder]
        val orderB = playerB[Players.seatOrder]
        // Use -1 as temporary to avoid unique constraint violation
        Players.update({ Players.id eq idA }) { it[Players.seatOrder] = -1 }
        Players.update({ Players.id eq idB }) { it[Players.seatOrder] = orderA }
        Players.update({ Players.id eq idA }) { it[Players.seatOrder] = orderB }
    }

    fun deleteDisconnected(roomId: UUID) = transaction {
        Players.deleteWhere { (Players.roomId eq roomId) and (Players.isConnected eq false) }
    }

    fun delete(id: UUID) = transaction {
        Players.deleteWhere { Players.id eq id }
    }

    fun compactSeatOrders(roomId: UUID) = transaction {
        val players = Players.selectAll()
            .where { Players.roomId eq roomId }
            .orderBy(Players.seatOrder to SortOrder.ASC)
            .map { it[Players.id].value to it[Players.seatOrder] }

        players.forEachIndexed { index, pair ->
            val playerId = pair.first
            Players.update({ Players.id eq playerId }) {
                it[seatOrder] = index
            }
        }
    }

    fun nextSeatOrder(roomId: UUID): Int = transaction {
        (Players.selectAll().where { Players.roomId eq roomId }
            .maxOfOrNull { it[Players.seatOrder] } ?: -1) + 1
    }

    private fun ResultRow.toPlayerRow() = PlayerRow(
        id = this[Players.id].value,
        roomId = this[Players.roomId].value,
        userName = this[Players.userName],
        seatOrder = this[Players.seatOrder],
        isHost = this[Players.isHost],
        isConnected = this[Players.isConnected]
    )
}
