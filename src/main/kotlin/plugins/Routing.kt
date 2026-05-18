package plugins

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import repository.PlayerRepository
import repository.RoomRepository
import routes.roomRoutes
import service.GameService
import service.RoomService
import websocket.ConnectionManager
import websocket.webSocketRoutes

fun Application.configureRouting() {
    val roomRepository = RoomRepository()
    val playerRepository = PlayerRepository()
    val connectionManager = ConnectionManager()
    val appScope = CoroutineScope(Dispatchers.Default)

    val roomService = RoomService(roomRepository, playerRepository)
    val gameService = GameService(connectionManager, roomRepository, playerRepository, appScope)

    routing {
        staticResources("/", "static", index = "index.html")
        roomRoutes(roomService, roomRepository, playerRepository)
        webSocketRoutes(connectionManager, gameService, roomRepository, playerRepository)
    }
}
