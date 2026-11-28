package com.openzeekr.app.ble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class DkRegionalCompatTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=UTF-8".toMediaType()

    @Test fun rewritesStaleLazyBaseUrlBeforeDownstreamInterceptors() {
        var activeBaseUrl = "https://la-snc-tsp-api-gw.zeekrlife.com"
        var observedUrl = ""
        val compat = DkRegionalCompat(
            baseUrlProvider = { activeBaseUrl },
            canSignKeyList = { true },
            deviceIdProvider = { "dev-123" },
            signProvider = { "sig-fresh" },
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(compat)
            .addInterceptor { chain ->
                observedUrl = chain.request().url.toString()
                jsonResponse(chain.request(), """{"code":"000000","data":{"phonePassword":"12345678"}}""")
            }
            .build()

        val staleEuReq = Request.Builder()
            .url("https://eu-snc-tsp-api-gw.zeekrlife.com/ms-tsp-dkbs-geely/api/v1.0/app/digital-key-center/pairing-code?foo=bar")
            .get()
            .build()
        client.newCall(staleEuReq).execute().close()
        assertEquals(
            "https://la-snc-tsp-api-gw.zeekrlife.com/ms-tsp-dkbs-geely/api/v1.0/app/digital-key-center/pairing-code?foo=bar",
            observedUrl,
        )

        activeBaseUrl = "https://sea-snc-tsp-api-gw.zeekrlife.com/"
        client.newCall(staleEuReq).execute().close()
        assertEquals(
            "https://sea-snc-tsp-api-gw.zeekrlife.com/ms-tsp-dkbs-geely/api/v1.0/app/digital-key-center/pairing-code?foo=bar",
            observedUrl,
        )
    }

    @Test fun keyListReturnsPrimaryWhenType2AlreadyHasEntries() {
        val observedTypes = mutableListOf<Int?>()
        val compat = DkRegionalCompat(
            baseUrlProvider = { "https://eu-snc-tsp-api-gw.zeekrlife.com" },
            canSignKeyList = { true },
            deviceIdProvider = { "dev-123" },
            signProvider = { "sig-fallback" },
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(compat)
            .addInterceptor { chain ->
                observedTypes += requestBodyType(chain.request())
                jsonResponse(chain.request(), """{"code":"000000","data":[{"dkId":"eu-key-1","dkType":2}]}""")
            }
            .build()

        val req = keyListRequest("""{"deviceId":"dev-123","dkType":2,"signature":"sig-orig","type":2}""")
        val body = client.newCall(req).execute().use { it.body!!.string() }
        val arr = json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray

        assertEquals(listOf(2), observedTypes)
        assertEquals("eu-key-1", arr[0].jsonObject["dkId"]!!.jsonPrimitive.content)
    }

    @Test fun keyListFallsBackToType1WhenType2IsEmpty() {
        val observedTypes = mutableListOf<Int?>()
        val observedSignatures = mutableListOf<String?>()
        var signCounter = 0
        val compat = DkRegionalCompat(
            baseUrlProvider = { "https://la-snc-tsp-api-gw.zeekrlife.com" },
            canSignKeyList = { true },
            deviceIdProvider = { "dev-la" },
            signProvider = { "sig-retry-${++signCounter}" },
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(compat)
            .addInterceptor { chain ->
                val reqObj = json.parseToJsonElement(readUtf8(chain.request())).jsonObject
                val t = reqObj["type"]?.jsonPrimitive?.intOrNull
                observedTypes += t
                observedSignatures += reqObj["signature"]?.jsonPrimitive?.content
                val respJson = if (t == 1) {
                    """{"code":"000000","data":[{"dkId":"la-owner-key","bookId":"8000a5cb","dkType":2,"dkStatus":3}]}"""
                } else {
                    """{"code":"000000","data":[]}"""
                }
                jsonResponse(chain.request(), respJson)
            }
            .build()

        val req = keyListRequest("""{"deviceId":"dev-la","dkType":2,"signature":"sig-initial","type":2}""")
        val body = client.newCall(req).execute().use { it.body!!.string() }
        val arr = json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray

        assertEquals(listOf(2, 1), observedTypes)
        assertEquals(listOf("sig-initial", "sig-retry-1"), observedSignatures)
        assertEquals("la-owner-key", arr[0].jsonObject["dkId"]!!.jsonPrimitive.content)
    }

    @Test fun keyListPreservesEmptyResultWhenBothType2AndType1AreEmpty() {
        val observedTypes = mutableListOf<Int?>()
        val compat = DkRegionalCompat(
            baseUrlProvider = { "https://la-snc-tsp-api-gw.zeekrlife.com" },
            canSignKeyList = { true },
            deviceIdProvider = { "dev-new" },
            signProvider = { "sig-retry" },
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(compat)
            .addInterceptor { chain ->
                observedTypes += requestBodyType(chain.request())
                jsonResponse(chain.request(), """{"code":"000000","data":[]}""")
            }
            .build()

        val req = keyListRequest("""{"deviceId":"dev-new","dkType":2,"signature":"sig-initial","type":2}""")
        val body = client.newCall(req).execute().use { it.body!!.string() }
        val root = json.parseToJsonElement(body).jsonObject

        assertEquals(listOf(2, 1), observedTypes)
        assertEquals("000000", root["code"]!!.jsonPrimitive.content)
        assertEquals(0, root["data"]!!.jsonArray.size)
    }

    private fun keyListRequest(jsonBody: String): Request =
        Request.Builder()
            .url("https://la-snc-tsp-api-gw.zeekrlife.com/ms-tsp-dkbs-geely/api/v1.0/app/digital-key-center/key-list")
            .post(jsonBody.toRequestBody(jsonMedia))
            .build()

    private fun requestBodyType(req: Request): Int? =
        json.parseToJsonElement(readUtf8(req)).jsonObject["type"]?.jsonPrimitive?.intOrNull

    private fun readUtf8(req: Request): String =
        Buffer().use { buf -> req.body!!.writeTo(buf); buf.readUtf8() }

    private fun jsonResponse(req: Request, body: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(jsonMedia))
            .build()
}
