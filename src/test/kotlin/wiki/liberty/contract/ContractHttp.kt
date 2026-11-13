// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.test.context.DynamicPropertyRegistry
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

internal const val operatorToken = "test-only-operator-token"
internal const val csrfToken = "+\\"
internal const val revisionFields = "ids|timestamp|content|contentmodel"

internal fun postgresProperties(properties: DynamicPropertyRegistry) {
    val url = checkNotNull(System.getenv("WIKI_TEST_DB_URL")) { "Set WIKI_TEST_DB_URL to a disposable PostgreSQL 17 database." }
    val schema = "page_contract_" + UUID.randomUUID().toString().replace("-", "")
    properties.add("spring.datasource.url") { url + (if ('?' in url) "&" else "?") + "currentSchema=$schema" }
    properties.add("spring.flyway.schemas") { schema }
    properties.add("spring.datasource.username") { System.getenv("WIKI_TEST_DB_USER") ?: "libertywiki" }
    properties.add("spring.datasource.password") { System.getenv("WIKI_TEST_DB_PASSWORD") ?: "" }
}

internal class ContractHttp(private val base: String, private val scriptPath: String = "/w") {
    fun get(path: String, auth: String? = null) = send(path, auth = auth)
    fun nativeCreate(title: String, source: String, auth: String? = operatorToken) = postJson(
        "/api/v1/pages", mapOf("title" to title, "namespaceId" to 0, "source" to source), auth,
    )

    fun restCreate(
        title: String,
        source: String,
        token: String? = csrfToken,
        auth: String? = null,
        comment: String? = "Initial source",
    ): Result {
        val body = mutableMapOf("title" to title, "source" to source, "comment" to comment)
        if (token != null) body["token"] = token
        return postJson("$scriptPath/rest.php/v1/page", body, auth)
    }

    fun restRead(title: String, auth: String? = null) =
        get(restPage(title), auth)

    fun restHistory(title: String, auth: String? = null, query: String = "") = get("${restPage(title)}/history$query", auth)
    fun restRevision(id: Long, auth: String? = null) = get("$scriptPath/rest.php/v1/revision/$id", auth)
    fun restCompare(from: Long, to: Long, auth: String? = null) =
        get("$scriptPath/rest.php/v1/revision/$from/compare/$to", auth)

    fun restEdit(title: String, source: String, base: Long, token: String? = csrfToken, auth: String? = null): Result {
        val body = mutableMapOf<String, Any>("source" to source, "latest" to mapOf("id" to base), "comment" to "Edit summary")
        if (token != null) body["token"] = token
        return send(restPage(title), mapper.writeValueAsString(body), "application/json", auth, "PUT")
    }

    private fun restPage(title: String) = "$scriptPath/rest.php/v1/page/${encoded(title).replace("+", "%20")}"

    fun actionQuery(vararg params: Pair<String, String>, auth: String? = null) =
        get("$scriptPath/api.php?" + form(mapOf("action" to "query") + params), auth)

    fun actionCompare(from: Long, to: Long, auth: String? = null) = get(
        "$scriptPath/api.php?" + form(mapOf("action" to "compare", "fromrev" to "$from", "torev" to "$to")), auth,
    )

    fun actionCreate(title: String, source: String, token: String = csrfToken, auth: String? = null): Result {
        val body = form(mapOf("action" to "edit", "title" to title, "text" to source,
            "createonly" to "1", "token" to token))
        return send("$scriptPath/api.php", body, "application/x-www-form-urlencoded", auth)
    }

    fun actionRead(key: Pair<String, String>, auth: String? = null) = actionQuery(
        "prop" to "revisions", key, "rvslots" to "main", "rvprop" to revisionFields, auth = auth,
    )

    fun actionEdit(key: Pair<String, String>, source: String, base: Long, token: String = csrfToken, auth: String? = null): Result {
        val body = form(mapOf("action" to "edit", key, "text" to source, "baserevid" to base.toString(),
            "nocreate" to "1", "summary" to "Edit summary", "token" to token))
        return send("$scriptPath/api.php", body, "application/x-www-form-urlencoded", auth)
    }

    fun actionUndo(
        key: Pair<String, String>, revision: Long, auth: String? = null, summary: String? = null, token: String = csrfToken,
        base: Long? = null,
    ): Result {
        val params = mutableMapOf("action" to "edit", key, "undo" to revision.toString(), "token" to token)
        if (summary != null) params["summary"] = summary
        if (base != null) params["baserevid"] = base.toString()
        return send("$scriptPath/api.php", form(params), "application/x-www-form-urlencoded", auth)
    }

    fun postJson(path: String, body: Any, auth: String? = null) =
        send(path, mapper.writeValueAsString(body), "application/json", auth)

    fun postText(path: String, body: String, auth: String? = null) =
        send(path, body, "text/plain", auth)

    private fun send(
        path: String, body: String? = null, type: String? = null, auth: String? = null, method: String = "POST",
    ): Result {
        val request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
        if (auth != null) request.header("Authorization", "Bearer $auth")
        if (body != null) request.header("Content-Type", checkNotNull(type)).method(method, HttpRequest.BodyPublishers.ofString(body))
        return Result(client.send(request.build(), HttpResponse.BodyHandlers.ofString()))
    }

    private fun form(values: Map<String, String>) = (values + mapOf("format" to "json", "formatversion" to "2"))
        .entries.joinToString("&") { (name, value) -> "$name=${encoded(value)}" }

    class Result(private val response: HttpResponse<String>) {
        val status get() = response.statusCode()
        val json: JsonNode get() = mapper.readTree(response.body())
        fun header(name: String) = response.headers().firstValue(name).orElseThrow()
    }

    companion object {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val mapper = ObjectMapper()
        private fun encoded(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    }
}
