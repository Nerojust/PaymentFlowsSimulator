package com.nerojust.paymentsim.server

import com.nerojust.paymentsim.client.api.dto.PaymentRequest
import com.nerojust.paymentsim.log.shortKey
import com.nerojust.paymentsim.network.FakeNetwork
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Answers every request itself and never calls chain.proceed, so nothing leaves the device.
 * Retrofit sees exactly what a real app would see: responses, 500s and IOExceptions.
 */
class FakeServerInterceptor(
    private val server: FakePaymentServer,
    private val network: FakeNetwork,
    private val json: Json,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        // The ledger is the audience's window into the server, so it is never failure-injected.
        if (request.method == "GET" && path == "/ledger") {
            return respond(request, runBlocking { server.ledger() })
        }

        val key = request.header(HEADER_IDEMPOTENCY_KEY) ?: path.substringAfterLast('/')
        val what = if (request.method == "POST") "payment ${key.shortKey()}" else "the check on payment ${key.shortKey()}"

        if (!network.beforeServer(what)) {
            return respond(request, FakePaymentServer.Reply(500, """{"error":"internal_server_error"}"""))
        }
        // Blocking is fine here: OkHttp runs interceptors on its own dispatcher threads.
        val reply = runBlocking { route(request, path) }
        network.afterServer(what, reply.code)
        return respond(request, reply)
    }

    private suspend fun route(request: Request, path: String): FakePaymentServer.Reply = when {
        request.method == "POST" && path == "/payments" -> server.createPayment(
            idempotencyKey = request.header(HEADER_IDEMPOTENCY_KEY),
            request = json.decodeFromString(PaymentRequest.serializer(), request.bodyAsString()),
        )
        request.method == "GET" && path.startsWith("/payments/") ->
            server.getPayment(path.removePrefix("/payments/"))
        else -> FakePaymentServer.Reply(404, """{"error":"no_such_route"}""")
    }

    private fun Request.bodyAsString(): String =
        Buffer().also { buffer -> body?.writeTo(buffer) }.readUtf8()

    private fun respond(request: Request, reply: FakePaymentServer.Reply): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message("Fake backend")
            .body(reply.body.toResponseBody(JSON_MEDIA_TYPE))
            .build()

    companion object {
        const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
