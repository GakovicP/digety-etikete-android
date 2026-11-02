package com.gakovic.etikete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Greska koju korisnik treba da vidi (poruka na srpskom). */
class ApiException(message: String, val notFound: Boolean = false, val uncertain: Boolean = false) :
    Exception(message)

/**
 * Digety System API (GraphQL) + AWS Cognito prijava.
 * Isti nalog kao za Digety web dashboard.
 */
object Api {
    private const val API_URL = "https://system.digital-pricing.com/api"
    private const val COGNITO_URL = "https://cognito-idp.eu-central-1.amazonaws.com/"
    private const val CLIENT_ID = "6c5op4e2t33fv3cd4oftkhe2du"
    private const val RETRIES = 3

    private val JSON = "application/json".toMediaType()
    private val AMZ_JSON = "application/x-amz-json-1.1".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val tokenLock = Mutex()
    @Volatile private var idToken: String? = null
    @Volatile private var tokenTime: Long = 0

    // ------------------------------------------------------------------ prijava

    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        val res = cognito(
            JSONObject()
                .put("AuthFlow", "USER_PASSWORD_AUTH")
                .put("ClientId", CLIENT_ID)
                .put("AuthParameters", JSONObject().put("USERNAME", username).put("PASSWORD", password))
        )
        if (res.has("ChallengeName")) {
            throw ApiException(
                "Nalog traži dodatni korak (${res.optString("ChallengeName")}). " +
                    "Prijavi se jednom u Digety web aplikaciju i promeni lozinku."
            )
        }
        val auth = res.getJSONObject("AuthenticationResult")
        Prefs.username = username
        Prefs.refreshToken = auth.optString("RefreshToken", Prefs.refreshToken ?: "")
        setToken(auth.getString("IdToken"))
    }

    fun logout() {
        idToken = null
        Prefs.refreshToken = null
        Prefs.storeId = null
        Prefs.storeName = null
    }

    val loggedIn: Boolean get() = !Prefs.refreshToken.isNullOrEmpty()

    private fun setToken(t: String) {
        idToken = t
        tokenTime = System.currentTimeMillis()
    }

    private suspend fun token(force: Boolean = false): String = tokenLock.withLock {
        val t = idToken
        if (!force && t != null && System.currentTimeMillis() - tokenTime < 50 * 60 * 1000L) return t
        val refresh = Prefs.refreshToken
        if (refresh.isNullOrEmpty()) throw ApiException("Nisi prijavljen. Otvori Podešavanja i prijavi se.")
        val res = withContext(Dispatchers.IO) {
            cognito(
                JSONObject()
                    .put("AuthFlow", "REFRESH_TOKEN_AUTH")
                    .put("ClientId", CLIENT_ID)
                    .put("AuthParameters", JSONObject().put("REFRESH_TOKEN", refresh))
            )
        }
        val newToken = res.getJSONObject("AuthenticationResult").getString("IdToken")
        setToken(newToken)
        newToken
    }

    private fun cognito(body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(COGNITO_URL)
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
            .post(body.toString().toRequestBody(AMZ_JSON))
            .build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val js = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (!r.isSuccessful) {
                    val msg = js.optString("message", text.take(200))
                    if (body.optString("AuthFlow") == "REFRESH_TOKEN_AUTH") {
                        Prefs.refreshToken = null
                        throw ApiException("Sesija je istekla - prijavi se ponovo u Podešavanjima. ($msg)")
                    }
                    throw ApiException("Prijava nije uspela: $msg")
                }
                return js
            }
        } catch (e: IOException) {
            throw ApiException("Nema veze sa serverom za prijavu (${e.message}).")
        }
    }

    // ------------------------------------------------------------------ GraphQL

    /** Izvrsava GraphQL upit; ponavlja kod timeout-a / 5xx; vraca "data". */
    suspend fun gql(query: String, variables: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            var tok = token()
            var authRetried = false
            var attempt = 0
            var lastErr: Exception? = null
            while (attempt < RETRIES) {
                attempt++
                val req = Request.Builder()
                    .url(API_URL)
                    .header("Authorization", tok)
                    .post(JSONObject().put("query", query).put("variables", variables).toString().toRequestBody(JSON))
                    .build()
                try {
                    http.newCall(req).execute().use { r ->
                        val text = r.body?.string().orEmpty()
                        if ((r.code == 401 || r.code == 403) && !authRetried) {
                            authRetried = true
                            attempt--
                            tok = token(force = true)
                            return@use
                        }
                        if (r.code in listOf(429, 500, 502, 503, 504) && attempt < RETRIES) {
                            lastErr = ApiException("HTTP ${r.code}")
                            return@use
                        }
                        val js = runCatching { JSONObject(text) }.getOrNull()
                            ?: throw ApiException("HTTP ${r.code}: ${text.take(200)}")
                        val errors = js.optJSONArray("errors")
                        if (errors != null && errors.length() > 0) {
                            val msg = (0 until errors.length()).joinToString("; ") {
                                errors.optJSONObject(it)?.optString("message") ?: errors.opt(it).toString()
                            }
                            throw ApiException(msg, notFound = msg.contains("not found", ignoreCase = true))
                        }
                        if (!r.isSuccessful) throw ApiException("HTTP ${r.code}: ${text.take(200)}")
                        return@withContext js.optJSONObject("data") ?: JSONObject()
                    }
                } catch (e: IOException) {
                    lastErr = e
                }
                if (attempt < RETRIES) delay(5000L * attempt * attempt)
            }
            throw ApiException(
                "Digety nije odgovorio ni posle $RETRIES pokušaja (${lastErr?.message ?: "timeout"}). " +
                    "Ne zna se da li je izvršeno - proveri etiketu.",
                uncertain = true
            )
        }

    // ------------------------------------------------------------------ korisnik i prodavnice

    suspend fun me(): JSONObject? =
        gql("query { user { id mail roles departments store { id name location { lat lon address } } } }")
            .optJSONObject("user")

    suspend fun stores(): List<Pair<String, String>> {
        val arr = gql("query { stores { id name } }").optJSONArray("stores") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { it.optString("id") to it.optString("name", it.optString("id")) }
            .sortedBy { it.second.lowercase() }
    }

    suspend fun storeDetails(storeId: String): JSONObject? = gql(
        """query(${'$'}id: ID!) { store(id: ${'$'}id) {
            id name location { lat lon address } departments
            stories { id name map { id floor image_url
                bounds { lower_left { x y } upper_right { x y } }
                zones(active: true) { id name type geometry department }
            } }
        } }""",
        JSONObject().put("id", storeId)
    ).optJSONObject("store")

    // ------------------------------------------------------------------ etikete

    private const val TAG_FIELDS = """
        id battery online offline_time kind current_image
        supported_features { led { enabled colors } }
        skus { sku { sku barcodes size color texts { type text } } price { prices { type price currency } } }
        location { latest_location { x y floor age_seconds source timestamp } }
        store { id name }
    """

    suspend fun tag(id: String): JSONObject? =
        gql("query(${'$'}id: ID!) { tag(id: ${'$'}id) { $TAG_FIELDS } }", JSONObject().put("id", id))
            .optJSONObject("tag")

    suspend fun tagsBySku(sku: String, store: String?): List<JSONObject> {
        val vars = JSONObject().put("sku", sku)
        if (store != null) vars.put("store", store)
        val arr = gql(
            "query(${'$'}sku: String, ${'$'}store: String) { tags(sku: ${'$'}sku, store: ${'$'}store) { $TAG_FIELDS } }",
            vars
        ).optJSONArray("tags") ?: JSONArray()
        return arr.objects()
    }

    /** Samo pozicije svih etiketa prodavnice (za mapu). */
    suspend fun tagPositions(store: String): List<JSONObject> {
        val arr = gql(
            "query(${'$'}store: String) { tags(store: ${'$'}store) { id online location { latest_location { x y floor age_seconds } } } }",
            JSONObject().put("store", store)
        ).optJSONArray("tags") ?: JSONArray()
        return arr.objects()
    }

    /** Status svih etiketa prodavnice (za izvestaj offline). */
    suspend fun tagStatus(store: String): List<JSONObject> {
        val arr = gql(
            "query(${'$'}store: String) { tags(store: ${'$'}store) { id online offline_time battery skus { sku { sku } } } }",
            JSONObject().put("store", store)
        ).optJSONArray("tags") ?: JSONArray()
        return arr.objects()
    }

    suspend fun lowBattery(store: String, upper: Int = 25): List<JSONObject> {
        val arr = gql(
            """query(${'$'}store: String, ${'$'}b: BatteryInput) {
                lowBatteryTags(store: ${'$'}store, battery_thresholds: ${'$'}b) {
                    id battery online offline_time skus { sku { sku texts { type text } } } } }""",
            JSONObject().put("store", store)
                .put("b", JSONObject().put("lower_threshold", 0).put("upper_threshold", upper))
        ).optJSONArray("lowBatteryTags") ?: JSONArray()
        return arr.objects()
    }

    /** Vezivanje: mode = "barcode" ili "sku". */
    suspend fun attach(tagId: String, product: String, mode: String) {
        val (field, arg) = if (mode == "sku") "attachBySkus" to "skus" else "attachByBarcodes" to "barcodes"
        val data = gql(
            "mutation(${'$'}t: [ID!], ${'$'}p: [String!]) { $field(tags: ${'$'}t, $arg: ${'$'}p) { id } }",
            JSONObject().put("t", JSONArray().put(tagId)).put("p", JSONArray().put(product))
        )
        val res = data.optJSONArray(field)
        if (res == null || res.length() == 0) {
            throw ApiException("Digety nije vratio etiketu - tag ili proizvod možda ne postoji.", notFound = true)
        }
    }

    suspend fun detach(tagId: String) {
        gql(
            "mutation(${'$'}t: [ID!]) { detach(tags: ${'$'}t) { id } }",
            JSONObject().put("t", JSONArray().put(tagId))
        )
    }

    suspend fun blink(tagId: String, seconds: Int = 10, color: String? = null) {
        runCatching {
            gql("mutation(${'$'}t: [ID!]) { precondition(tags: ${'$'}t) { id } }", JSONObject().put("t", JSONArray().put(tagId)))
        }
        val vars = JSONObject().put("t", JSONArray().put(tagId)).put("d", seconds)
        if (color != null) vars.put("c", color)
        gql(
            "mutation(${'$'}t: [ID!], ${'$'}d: Int!, ${'$'}c: String) { makeLedBlink(tags: ${'$'}t, duration: ${'$'}d, color: ${'$'}c) { id } }",
            vars
        )
    }

    /** Postoji li tag u Digety-ju (za razlikovanje "tag ne postoji" od "proizvod ne postoji"). */
    suspend fun tagExists(tagId: String): Boolean =
        try {
            tag(tagId) != null
        } catch (e: ApiException) {
            if (e.notFound) false else throw e
        }

    // ------------------------------------------------------------------ alarmi

    suspend fun alarms(store: String, start: String?, type: String?, cursor: String?, limit: Int = 50): Pair<List<JSONObject>, String?> {
        val vars = JSONObject().put("id", store).put("limit", limit)
        if (start != null) vars.put("start", start)
        if (type != null) vars.put("type", type)
        if (cursor != null) vars.put("cursor", cursor)
        val st = gql(
            """query(${'$'}id: ID!, ${'$'}start: String, ${'$'}type: String, ${'$'}cursor: String, ${'$'}limit: Int) {
                store(id: ${'$'}id) {
                    alarmsPaginated(start_date: ${'$'}start, type: ${'$'}type, cursor: ${'$'}cursor, limit: ${'$'}limit) {
                        items { map start zone zones x y tag_missing type tag { id battery online skus { sku { sku texts { type text } } } } }
                        next_cursor
                    }
                } }""",
            vars
        ).optJSONObject("store")
        val page = st?.optJSONObject("alarmsPaginated") ?: return emptyList<JSONObject>() to null
        val next = page.optString("next_cursor").takeIf { it.isNotEmpty() && it != "null" }
        return (page.optJSONArray("items") ?: JSONArray()).objects() to next
    }

    // ------------------------------------------------------------------ prijave problema (issues)

    private const val ISSUE_FIELDS = """
        id store tag_id category description photo_url status reporter_name created_at resolved_at
        diagnostic { tag_online tag_offline_time tag_battery last_ap_name last_ap_online last_update_succeeded
                     last_update_time system_price price_mismatch sku item_name advice }
        comments { id author_name text created_at }
    """

    suspend fun issues(store: String, status: String?): List<JSONObject> {
        val vars = JSONObject().put("store", store).put("limit", 100)
        if (status != null) vars.put("status", status)
        val page = gql(
            "query(${'$'}store: ID, ${'$'}status: IssueStatus, ${'$'}limit: Int) { issues(store: ${'$'}store, status: ${'$'}status, limit: ${'$'}limit) { items { $ISSUE_FIELDS } next_cursor } }",
            vars
        ).optJSONObject("issues")
        return (page?.optJSONArray("items") ?: JSONArray()).objects()
    }

    suspend fun uploadIssuePhoto(store: String, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val u = gql(
            "query(${'$'}store: ID!, ${'$'}f: String!) { issuePhotoUploadUrl(store: ${'$'}store, fileName: ${'$'}f) { uploadUrl s3Key } }",
            JSONObject().put("store", store).put("f", "foto_${System.currentTimeMillis()}.jpg")
        ).getJSONObject("issuePhotoUploadUrl")
        val req = Request.Builder()
            .url(u.getString("uploadUrl"))
            .put(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("Slanje fotografije nije uspelo (HTTP ${r.code}).")
        }
        u.getString("s3Key")
    }

    suspend fun createIssue(
        store: String, tagId: String, category: String, description: String,
        reporter: String?, photoKey: String?
    ): JSONObject {
        val input = JSONObject()
            .put("store", store).put("tag_id", tagId).put("category", category)
            .put("description", description)
        if (!reporter.isNullOrBlank()) input.put("reporter_name", reporter)
        if (photoKey != null) input.put("photo_s3_key", photoKey)
        return gql(
            "mutation(${'$'}i: CreateIssueInput!) { createIssue(issue: ${'$'}i) { $ISSUE_FIELDS } }",
            JSONObject().put("i", input)
        ).getJSONObject("createIssue")
    }

    suspend fun commentIssue(id: String, text: String, name: String?): JSONObject {
        val vars = JSONObject().put("id", id).put("text", text)
        if (!name.isNullOrBlank()) vars.put("name", name)
        return gql(
            "mutation(${'$'}id: ID!, ${'$'}text: String!, ${'$'}name: String) { commentOnIssue(id: ${'$'}id, text: ${'$'}text, name: ${'$'}name) { $ISSUE_FIELDS } }",
            vars
        ).getJSONObject("commentOnIssue")
    }

    suspend fun resolveIssue(id: String): JSONObject =
        gql("mutation(${'$'}id: ID!) { resolveIssue(id: ${'$'}id) { $ISSUE_FIELDS } }", JSONObject().put("id", id))
            .getJSONObject("resolveIssue")
}

// ---------------------------------------------------------------------- pomocne funkcije za JSON

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Naziv proizvoda na etiketi (prvi SKU). */
fun JSONObject.tagProductName(): String? {
    val skus = optJSONArray("skus")?.objects().orEmpty()
    val sku = skus.firstOrNull()?.optJSONObject("sku") ?: return null
    val texts = sku.optJSONArray("texts")?.objects().orEmpty()
    return texts.firstOrNull { it.optString("type") == "name" }?.str("text")
        ?: texts.firstOrNull()?.str("text")
        ?: sku.str("sku")
}

fun JSONObject.tagSku(): String? =
    optJSONArray("skus")?.objects()?.firstOrNull()?.optJSONObject("sku")?.str("sku")

fun JSONObject.tagPrice(): String? {
    val prices = optJSONArray("skus")?.objects()?.firstOrNull()
        ?.optJSONObject("price")?.optJSONArray("prices")?.objects().orEmpty()
    val p = prices.firstOrNull() ?: return null
    return "%.2f %s".format(p.optDouble("price"), p.optString("currency", ""))
}
