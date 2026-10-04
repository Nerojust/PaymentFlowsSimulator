package com.nerojust.paymentsim.client.api

import com.nerojust.paymentsim.client.api.dto.ChargeDto
import com.nerojust.paymentsim.client.api.dto.PaymentRequest
import com.nerojust.paymentsim.client.api.dto.PaymentResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface PaymentApi {

    @POST("payments")
    suspend fun createPayment(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: PaymentRequest,
    ): Response<PaymentResponse>

    @GET("payments/{key}")
    suspend fun getPayment(@Path("key") key: String): Response<PaymentResponse>

    @GET("ledger")
    suspend fun ledger(): List<ChargeDto>
}
