package plugins

import database.DatabaseFactory
import io.ktor.server.application.*

fun Application.configureDatabase() {
    DatabaseFactory.init(this)
}
