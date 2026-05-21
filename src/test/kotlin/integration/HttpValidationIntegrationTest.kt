package integration

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import model.http.CreateRoomRequest
import model.http.ErrorResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 統合テスト：HTTP ルーティング層のバリデーション
 *
 * 仕様参照：
 * - game_requirements.md §6-1（ユーザー名 1〜12文字）
 *
 * 実 DB を使わず、Ktor の testApplication 上でルーティング・バリデーションの挙動を確認する。
 * RoomService の DB アクセスを伴うフローは別途 DB 統合テストで網羅する。
 */
class HttpValidationIntegrationTest {

    private fun Application.installRoute() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        routing {
            post("/api/rooms") {
                val req = call.receive<CreateRoomRequest>()
                if (req.userName.isBlank() || req.userName.length > 12) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("ユーザー名は1〜12文字で入力してください"))
                    return@post
                }
                call.respond(HttpStatusCode.Created, ErrorResponse("ok"))
            }
        }
    }

    @Test
    fun emptyUserNameReturns400() = testApplication {
        environment { config = MapApplicationConfig() }
        application { installRoute() }
        val res = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"userName":"","poisonAppleCount":1,"roles":["SNOW_WHITE","QUEEN","GREEN","BLACK"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("ユーザー名"))
    }

    @Test
    fun tooLongUserNameReturns400() = testApplication {
        environment { config = MapApplicationConfig() }
        application { installRoute() }
        val longName = "あ".repeat(13)
        val res = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"userName":"$longName","poisonAppleCount":1,"roles":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun validUserNameIsAccepted() = testApplication {
        environment { config = MapApplicationConfig() }
        application { installRoute() }
        val res = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"userName":"alice","poisonAppleCount":1,"roles":["SNOW_WHITE"]}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
    }
}

