// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

@Tag("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "wiki.pages.anonymous-read=false",
    "wiki.pages.anonymous-edit=true",
    "wiki.pages.anonymous-create=true",
    "wiki.http.operator-token=$operatorToken",
])
class PagePostgresTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val http get() = ContractHttp("http://127.0.0.1:$port")
    private val native = "/api/v1/pages"
    private val source = "== Source ==\r\n<script>literal</script>\n"

    @Test
    fun `comparison preserves direction and literal Wikitext across all read surfaces`() {
        val title = "Compare ${UUID.randomUUID()}"
        val first = http.nativeCreate(title, "{|\n| {{틀|한😀}}\n|}\n").json
        val pageId = first["id"].asText()
        val oldId = first["currentRevisionId"].asText()
        val compatOld = http.restRead(title.replace(' ', '_'), operatorToken).json.at("/latest/id").asLong()
        val literal = "<img src=x onerror=alert(1)>"
        val second = http.postJson("$native/$pageId/revisions", mapOf(
            "baseRevisionId" to oldId, "source" to "{|\n| {{틀|한🌱}}\n|}\n$literal\n",
        ), operatorToken).json
        val newId = second["currentRevisionId"].asText()
        val compatNew = http.restRead(title.replace(' ', '_'), operatorToken).json.at("/latest/id").asLong()
        assertThat(compatNew).isGreaterThan(compatOld)
        assertThat(compatOld).isPositive()
        val before = rowCounts()
        val operation = "/paths/~1revisions~1{fromId}~1compare~1{toId}/get/responses"

        fun nativeCompare(from: String, to: String) = http.get("/api/v1/revisions/$from/compare/$to", operatorToken)

        val forward = nativeCompare(oldId, newId)
        assertThat(forward.status).isEqualTo(200)
        assertThat(forward.header("Cache-Control")).isEqualTo("no-store")
        assertSchema("$operation/200", forward.json)
        assertThat(forward.json.at("/from/id").asText()).isEqualTo(oldId)
        assertThat(forward.json.at("/to/id").asText()).isEqualTo(newId)
        val changed = forward.json["lines"].first { it["type"].asText() == "changed" }
        assertThat(changed["from"].filter { it["changed"].asBoolean() }.joinToString("") { it["text"].asText() }).isEqualTo("😀")
        assertThat(changed["to"].filter { it["changed"].asBoolean() }.joinToString("") { it["text"].asText() }).isEqualTo("🌱")
        assertThat(nativeCompare(newId, oldId).json["lines"].map { it["type"].asText() }).contains("deleted")
        assertThat(nativeCompare(oldId, oldId).json["lines"].size()).isZero()

        val action = http.actionCompare(compatOld, compatNew, operatorToken)
        assertThat(action.status).isEqualTo(200)
        assertThat(action.json.at("/compare/fromrevid").asLong()).isEqualTo(compatOld)
        assertThat(action.json.at("/compare/torevid").asLong()).isEqualTo(compatNew)
        assertThat(action.json.at("/compare/body").asText()).contains("&lt;img").doesNotContain("<img")
        val rest = http.restCompare(compatOld, compatNew, operatorToken)
        assertThat(rest.status).isEqualTo(200)
        assertThat(rest.json.at("/from/id").asLong()).isEqualTo(compatOld)
        assertThat(rest.json.at("/to/id").asLong()).isEqualTo(compatNew)
        assertThat(rest.json["diff"].map { it["type"].asInt() }).contains(1, 3)

        assertThat(http.get("$native/$pageId", operatorToken).json).isEqualTo(second)
        assertThat(rowCounts()).isEqualTo(before)
    }

    @Test
    fun `comparison enforces read policy and rejects unavailable unrelated and oversized inputs without writes`() {
        val title = "Private ${UUID.randomUUID()}"
        val source = "private source\n".repeat(2_001)
        val page = http.nativeCreate(title, source).json
        val pageId = page["id"].asText()
        val revision = page["currentRevisionId"].asText()
        val otherTitle = "Unrelated ${UUID.randomUUID()}"
        val unrelated = http.nativeCreate(otherTitle, "unrelated source").json["currentRevisionId"].asText()
        val compatId = http.restRead(title.replace(' ', '_'), operatorToken).json.at("/latest/id").asLong()
        val unrelatedCompatId = http.restRead(otherTitle.replace(' ', '_'), operatorToken).json.at("/latest/id").asLong()
        assertThat(unrelatedCompatId).isGreaterThan(compatId)
        assertThat(compatId).isPositive()
        val missing = UUID.randomUUID().toString()
        val missingCompatId = 9_999_999L
        val before = rowCounts()
        val operation = "/paths/~1revisions~1{fromId}~1compare~1{toId}/get/responses"

        fun nativeCompare(from: String, to: String, auth: String? = operatorToken) =
            http.get("/api/v1/revisions/$from/compare/$to", auth)

        fun checkNative(result: ContractHttp.Result, status: Int, code: String) {
            assertThat(result.status).isEqualTo(status)
            assertThat(result.json["code"].asText()).isEqualTo(code)
            assertSchema("$operation/$status", result.json)
        }
        for (pair in listOf(revision to missing, missing to revision, revision to unrelated, revision to revision)) {
            checkNative(nativeCompare(pair.first, pair.second, null), 403, "FORBIDDEN")
        }
        for (pair in listOf(compatId to missingCompatId, missingCompatId to compatId, compatId to unrelatedCompatId, compatId to compatId)) {
            val action = http.actionCompare(pair.first, pair.second)
            assertThat(action.json.at("/error/code").asText()).isEqualTo("readapidenied")
            assertThat(action.json.has("compare")).isFalse()
            val rest = http.restCompare(pair.first, pair.second)
            assertThat(rest.status).isEqualTo(403)
            assertThat(rest.json.has("diff")).isFalse()
        }

        checkNative(nativeCompare(revision, missing), 404, "REVISION_NOT_FOUND")
        checkNative(nativeCompare(missing, revision), 404, "REVISION_NOT_FOUND")
        checkNative(nativeCompare(revision, unrelated), 400, "REVISION_PAGE_MISMATCH")
        checkNative(nativeCompare(revision, "invalid"), 422, "INVALID_REVISION_ID")
        checkNative(nativeCompare(revision, revision), 422, "COMPARISON_TOO_LARGE")
        checkNative(nativeCompare(revision, revision, "wrong-credential"), 401, "INVALID_CREDENTIALS")
        assertThat(http.actionCompare(compatId, unrelatedCompatId, operatorToken).json.at("/error/code").asText())
            .isEqualTo("unsupported")
        assertThat(http.actionCompare(compatId, compatId, operatorToken).json.at("/error/code").asText())
            .isEqualTo("comparison-too-large")
        assertThat(http.restCompare(compatId, unrelatedCompatId, operatorToken).status).isEqualTo(400)
        assertThat(http.restCompare(compatId, compatId, operatorToken).status).isEqualTo(422)
        assertThat(http.get("$native/$pageId", operatorToken).json).isEqualTo(page)
        assertThat(rowCounts()).isEqualTo(before)
    }

    @Test
    fun `REST comparison limits serialized section metadata even for identical source`() {
        val title = "Large-heading-${UUID.randomUUID()}"
        val source = "== ${"\u0001".repeat(350_000)} =="
        val created = http.nativeCreate(title, source)
        assertThat(created.status).isEqualTo(201)
        val id = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        assertThat(id).isPositive()
        val comparison = http.restCompare(id, id, operatorToken)
        assertThat(comparison.status).isEqualTo(422)
        assertThat(comparison.json["errorKey"].asText()).isEqualTo("comparison-too-large")

        val updated = http.restEdit(title, source, id, auth = operatorToken)
        assertThat(updated.status).isEqualTo(200)
        val before = rowCounts()
        val conflict = http.restEdit(title, "Draft", id, auth = operatorToken)
        assertThat(conflict.status).isEqualTo(409)
        assertThat(conflict.json["errorKey"].asText()).isEqualTo("editconflict")
        assertThat(conflict.json.has("diff")).isFalse()
        assertThat(http.restRead(title, operatorToken).json.at("/latest/id")).isEqualTo(updated.json.at("/latest/id"))
        assertThat(rowCounts()).isEqualTo(before)
    }

    @Test
    fun `native revision history returns immutable source newest first`() {
        val created = http.nativeCreate("History ${UUID.randomUUID()}", source).json
        val pageId = created["id"].asText()
        val first = created["currentRevisionId"].asText()
        val edited = http.postJson("$native/$pageId/revisions",
            mapOf("baseRevisionId" to first, "source" to "Second", "summary" to "Second edit"), operatorToken).json
        val second = edited["currentRevisionId"].asText()
        val history = http.get("$native/$pageId/revisions?limit=1", operatorToken)
        assertThat(history.status).isEqualTo(200)
        assertSchema("/paths/~1pages~1{pageId}~1revisions/get/responses/200", history.json)
        assertThat(history.json.at("/revisions/0/id").asText()).isEqualTo(second)
        assertThat(history.json["nextCursor"].asText()).isEqualTo(second)
        val appended = http.postJson("$native/$pageId/revisions",
            mapOf("baseRevisionId" to second, "source" to "Third"), operatorToken)
        assertThat(appended.status).isEqualTo(201)
        val third = appended.json["currentRevisionId"].asText()
        val beforeReads = rowCounts()
        val next = http.get("$native/$pageId/revisions?limit=1&cursor=$second", operatorToken)
        assertThat(next.status).isEqualTo(200)
        assertThat(next.json.at("/revisions/0/id").asText()).isEqualTo(first)
        assertThat(next.json["nextCursor"].isNull).isTrue()
        val old = http.get("/api/v1/revisions/$first", operatorToken)
        assertThat(old.status).isEqualTo(200)
        assertSchema("/paths/~1revisions~1{revisionId}/get/responses/200", old.json)
        assertThat(old.json["source"].asText()).isEqualTo(source)
        assertThat(old.json["pageId"].asText()).isEqualTo(pageId)
        assertThat(old.json["normalizedTitle"]).isEqualTo(created["normalizedTitle"])
        assertThat(old.json["contentModel"].asText()).isEqualTo("wikitext")
        assertThat(old.json["parentRevisionId"].isNull).isTrue()
        assertThat(history.json.at("/revisions/0/parentRevisionId").asText()).isEqualTo(first)
        assertThat(history.json.at("/revisions/0/summary").asText()).isEqualTo("Second edit")
        assertThat(http.get("$native/$pageId/revisions", operatorToken).json["revisions"].map { it["id"].asText() })
            .containsExactly(third, second, first)
        assertThat(http.get("$native/$pageId", operatorToken).json["currentRevisionId"].asText()).isEqualTo(third)
        assertThat(rowCounts()).isEqualTo(beforeReads)
        val absent = http.get("/api/v1/revisions/${UUID.randomUUID()}", operatorToken)
        assertThat(absent.status).isEqualTo(404)
        assertThat(absent.json["code"].asText()).isEqualTo("REVISION_NOT_FOUND")
        assertSchema("/paths/~1revisions~1{revisionId}/get/responses/404", absent.json)
        val other = http.nativeCreate("Other ${UUID.randomUUID()}", "Other").json["currentRevisionId"].asText()
        val beforeInvalidReads = rowCounts()
        for (cursor in listOf("not-a-uuid", other)) {
            val invalid = http.get("$native/$pageId/revisions?cursor=$cursor", operatorToken)
            assertThat(invalid.status).isEqualTo(400)
            assertThat(invalid.json["code"].asText()).isEqualTo("INVALID_CURSOR")
            assertSchema("/paths/~1pages~1{pageId}~1revisions/get/responses/400", invalid.json)
        }
        assertThat(rowCounts()).isEqualTo(beforeInvalidReads)
    }

    @Test
    fun `history and old source apply private read policy on every surface without leaking existence`() {
        val title = "Private history ${UUID.randomUUID()}"
        val created = http.nativeCreate(title, source).json
        val pageId = created["id"].asText()
        val first = created["currentRevisionId"].asText()
        val compatId = http.actionRead("titles" to title, operatorToken).json.at("/query/pages/0/revisions/0/revid").asLong()
        val edited = http.postJson("$native/$pageId/revisions",
            mapOf("baseRevisionId" to first, "source" to "Second", "summary" to "Private summary"), operatorToken)
        assertThat(edited.status).isEqualTo(201)
        val beforeReads = rowCounts()
        val missing = UUID.randomUUID()
        val nativeReads = listOf(
            http.get("$native/$pageId/revisions"), http.get("$native/$missing/revisions"),
            http.get("/api/v1/revisions/$first"), http.get("/api/v1/revisions/$missing"),
        )
        for (response in nativeReads) {
            assertThat(response.status).isEqualTo(403)
            assertThat(response.header("Cache-Control")).isEqualTo("no-store")
            assertThat(response.json.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("code", "message", "requestId")
            assertThat(response.json["code"].asText()).isEqualTo("FORBIDDEN")
        }
        val actionReads = listOf(
            http.actionQuery("prop" to "revisions", "titles" to title, "rvlimit" to "2"),
            http.actionQuery("prop" to "revisions", "titles" to "Missing $missing", "rvlimit" to "2"),
            http.actionRead("revids" to compatId.toString()), http.actionRead("revids" to "999999999"),
        )
        for (response in actionReads) {
            assertThat(response.status).isEqualTo(200)
            assertThat(response.header("Cache-Control")).isEqualTo("no-store")
            assertThat(response.json.fieldNames().asSequence().toList()).containsExactly("error")
            assertThat(response.json.at("/error/code").asText()).isEqualTo("readapidenied")
        }
        val restReads = listOf(
            http.restHistory(title.replace(' ', '_')), http.restHistory("Missing_$missing"),
            http.restRevision(compatId), http.restRevision(999999999),
        )
        for (response in restReads) {
            assertThat(response.status).isEqualTo(403)
            assertThat(response.header("Cache-Control")).isEqualTo("no-store")
            assertThat(response.json["errorKey"].asText()).isEqualTo("rest-permission-denied")
            assertThat(response.json).isEqualTo(restReads.first().json)
        }
        val allowedAction = http.actionRead("revids" to compatId.toString(), operatorToken)
        val allowedRest = http.restRevision(compatId, operatorToken)
        assertThat(allowedAction.json.at("/query/pages/0/revisions/0/slots/main/content").asText()).isEqualTo(source)
        assertThat(allowedRest.json["source"].asText()).isEqualTo(source)
        assertThat(rowCounts()).isEqualTo(beforeReads)
    }

    @Test
    fun `native history applies its default and maximum page sizes`() {
        val title = "History_size_${UUID.randomUUID()}"
        val created = http.nativeCreate(title, source).json
        val path = "$native/${created["id"].asText()}/revisions"
        var revisionId = created["currentRevisionId"].asText()
        val ids = mutableListOf(revisionId)
        repeat(20) { index ->
            val edit = http.postJson(path,
                mapOf("baseRevisionId" to revisionId, "source" to "Revision $index"), operatorToken)
            assertThat(edit.status).isEqualTo(201)
            revisionId = edit.json["currentRevisionId"].asText()
            ids.add(revisionId)
        }
        val defaultPage = http.get(path, operatorToken)
        assertThat(defaultPage.status).isEqualTo(200)
        assertThat(defaultPage.json["revisions"].map { it["id"].asText() })
            .containsExactlyElementsOf(ids.asReversed().take(20))
        assertThat(defaultPage.json["nextCursor"].asText()).isEqualTo(ids[1])
        val maxPage = http.get("$path?limit=50", operatorToken)
        assertThat(maxPage.status).isEqualTo(200)
        assertThat(maxPage.json["revisions"].map { it["id"].asText() })
            .containsExactlyElementsOf(ids.asReversed())
        assertThat(maxPage.json["nextCursor"].isNull).isTrue()
        assertThat(http.get("$path?limit=51", operatorToken).status).isEqualTo(400)
        assertThat(http.get("$path?limit=0", operatorToken).status).isEqualTo(400)

        val action = http.actionQuery("prop" to "revisions", "titles" to title, "rvprop" to "ids",
            "rvlimit" to "21", auth = operatorToken)
        val numericIds = action.json.at("/query/pages/0/revisions").map { it["revid"].asLong() }
        assertThat(numericIds).hasSize(21)
        val rest = http.restHistory(title, operatorToken)
        assertThat(rest.json["revisions"].map { it["id"].asLong() }).containsExactlyElementsOf(numericIds.take(20))
        assertThat(rest.json["older"].asText()).endsWith("?older_than=${numericIds[19]}")
        val older = http.restHistory(title, operatorToken, "?older_than=${numericIds[19]}")
        assertThat(older.json["revisions"].map { it["id"].asLong() }).containsExactly(numericIds.last())
        assertThat(older.json["newer"].asText()).endsWith("?newer_than=${numericIds.last()}")
        val newer = http.restHistory(title, operatorToken, "?newer_than=${numericIds.last()}")
        assertThat(newer.json["revisions"].map { it["id"].asLong() }).containsExactlyElementsOf(numericIds.take(20))
    }

    @Test
    fun `native commit appends a revision and rejects a stale base`() {
        val created = http.nativeCreate("Edit ${UUID.randomUUID()}", source).json
        val pageId = created["id"].asText()
        val base = created["currentRevisionId"].asText()
        val path = "$native/$pageId/revisions"
        val before = rowCounts()
        val body = mapOf("baseRevisionId" to base, "source" to "Updated 원문")
        val operation = "/paths/~1pages~1{pageId}~1revisions/post"
        validateSchema(spec.at("$operation/requestBody/content/application~1json/schema"), yaml.valueToTree(body))
        val absent = http.postJson("$native/${UUID.randomUUID()}/revisions", body, operatorToken)
        assertThat(absent.status).isEqualTo(404)
        assertSchema("$operation/responses/404", absent.json)
        val missingBase = http.postJson(path, mapOf("source" to "Updated 원문"), operatorToken)
        assertThat(missingBase.status).isEqualTo(400)
        assertSchema("$operation/responses/400", missingBase.json)
        val invalidBase = http.postJson(path, body + ("baseRevisionId" to "1-1-1-1-1"), operatorToken)
        assertThat(invalidBase.status).isEqualTo(422)
        assertThat(invalidBase.json["code"].asText()).isEqualTo("INVALID_BASE_REVISION_ID")
        assertThat(rowCounts()).isEqualTo(before)
        val edited = http.postJson(path, body, operatorToken)
        assertThat(edited.status).isEqualTo(201)
        assertSchema("/paths/~1pages~1{pageId}~1revisions/post/responses/201", edited.json)
        assertThat(edited.json["currentRevisionId"].asText()).isNotEqualTo(base)
        assertThat(edited.json["source"].asText()).isEqualTo("Updated 원문")
        val stale = http.postJson(path, mapOf("baseRevisionId" to base, "source" to "Stale draft"), operatorToken)
        assertThat(stale.status).isEqualTo(409)
        assertSchema("/paths/~1pages~1{pageId}~1revisions/post/responses/409", stale.json)
        assertThat(stale.json["code"].asText()).isEqualTo("EDIT_CONFLICT")
        assertThat(stale.json["currentRevisionId"].asText()).isEqualTo(edited.json["currentRevisionId"].asText())
        val expected = http.get("/api/v1/revisions/$base/compare/${edited.json["currentRevisionId"].asText()}", operatorToken)
        assertThat(expected.status).isEqualTo(200)
        assertThat(stale.json["comparison"]).isEqualTo(expected.json)
        assertThat(stale.json.toString()).doesNotContain("Stale draft")
        assertThat(http.get("$native/$pageId", operatorToken).json).isEqualTo(edited.json)
        assertThat(rowCounts()).containsExactly(before[0], before[1] + 1, before[2] + 1, before[3] + 1)
        assertThat(jdbc.queryForObject(
            "SELECT source FROM wiki_revision WHERE id = ?", String::class.java, UUID.fromString(base),
        )).isEqualTo(source)
    }

    @Test
    fun `conflicts preserve state and unavailable or oversized bases fall back without a diff`() {
        val title = "Conflict-${UUID.randomUUID()}"
        val created = http.nativeCreate(title, "base\n").json
        val pageId = created["id"].asText()
        val base = created["currentRevisionId"].asText()
        val compatBase = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        val path = "$native/$pageId/revisions"
        val head = http.postJson(path, mapOf("baseRevisionId" to base, "source" to "published head\n"), operatorToken).json
        val headId = head["currentRevisionId"].asText()
        val foreign = http.nativeCreate("Foreign-${UUID.randomUUID()}", "foreign secret").json["currentRevisionId"].asText()
        val before = rowCounts()
        val expected = http.get("/api/v1/revisions/$base/compare/$headId", operatorToken).json
        val conflict = http.postJson(path, mapOf("baseRevisionId" to base, "source" to "draft secret"), operatorToken)
        assertThat(conflict.status).isEqualTo(409)
        assertSchema("/paths/~1pages~1{pageId}~1revisions/post/responses/409", conflict.json)
        assertThat(conflict.json["currentRevisionId"].asText()).isEqualTo(headId)
        assertThat(conflict.json["comparison"]).isEqualTo(expected)
        assertThat(conflict.json.toString()).doesNotContain("draft secret")
        val rest = http.restEdit(title, "REST draft secret", compatBase, auth = operatorToken)
        assertThat(rest.status).isEqualTo(409)
        val compatHead = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        val restComparison = http.restCompare(compatBase, compatHead, operatorToken).json
        for (field in listOf("from", "to", "diff")) {
            assertThat(rest.json[field]).isEqualTo(restComparison[field])
        }
        assertThat(rest.json.toString()).doesNotContain("REST draft secret")
        val action = http.actionEdit("title" to title, "Action draft secret", compatBase, auth = operatorToken)
        assertThat(action.json.at("/error/code").asText()).isEqualTo("editconflict")
        assertThat(action.json.has("compare")).isFalse()
        for (unavailable in listOf(UUID.randomUUID().toString(), foreign)) {
            val fallback = http.postJson(path, mapOf("baseRevisionId" to unavailable, "source" to "draft secret"), operatorToken)
            assertThat(fallback.status).isEqualTo(409)
            assertSchema("/paths/~1pages~1{pageId}~1revisions/post/responses/409", fallback.json)
            assertThat(fallback.json["currentRevisionId"].asText()).isEqualTo(headId)
            assertThat(fallback.json.has("comparison")).isFalse()
            assertThat(fallback.json.toString()).doesNotContain("foreign secret", "draft secret", "published head")
        }
        assertThat(http.get("$native/$pageId", operatorToken).json).isEqualTo(head)
        assertThat(rowCounts()).isEqualTo(before)

        val largeTitle = "Large-conflict-${UUID.randomUUID()}"
        val large = http.nativeCreate(largeTitle, "line\n".repeat(4_000)).json
        val compatLargeBase = http.restRead(largeTitle, operatorToken).json.at("/latest/id").asLong()
        val largePath = "$native/${large["id"].asText()}/revisions"
        val largeBase = large["currentRevisionId"].asText()
        val largeHead = http.postJson(largePath, mapOf("baseRevisionId" to largeBase, "source" to "new head"), operatorToken).json
        val largeBefore = rowCounts()
        val limited = http.postJson(largePath, mapOf("baseRevisionId" to largeBase, "source" to "draft"), operatorToken)
        assertThat(limited.status).isEqualTo(409)
        assertSchema("/paths/~1pages~1{pageId}~1revisions/post/responses/409", limited.json)
        assertThat(limited.json["currentRevisionId"]).isEqualTo(largeHead["currentRevisionId"])
        assertThat(limited.json.has("comparison")).isFalse()
        val limitedRest = http.restEdit(largeTitle, "draft", compatLargeBase, auth = operatorToken)
        assertThat(limitedRest.status).isEqualTo(409)
        assertThat(limitedRest.json.has("diff")).isFalse()
        val limitedAction = http.actionEdit("title" to largeTitle, "draft", compatLargeBase, auth = operatorToken)
        assertThat(limitedAction.status).isEqualTo(200)
        assertThat(limitedAction.header("MediaWiki-API-Error")).isEqualTo("editconflict")
        assertThat(http.get("$native/${large["id"].asText()}", operatorToken).json).isEqualTo(largeHead)
        assertThat(rowCounts()).isEqualTo(largeBefore)
    }

    @Test
    fun `edit surfaces share the revision chain and events`() {
        val title = "Chain-${UUID.randomUUID()}"
        var current = http.nativeCreate(title, source).json
        val pageId = current["id"].asText()
        val before = rowCounts()
        repeat(3) { surface ->
            val compat = http.restRead(title, operatorToken).json
            val compatBase = compat.at("/latest/id").asLong()
            val base = current["currentRevisionId"].asText()
            val updatedSource = "Updated source $surface"
            val response = when (surface) {
                0 -> http.postJson("$native/$pageId/revisions",
                    mapOf("baseRevisionId" to base, "source" to updatedSource, "summary" to "Edit summary"), operatorToken)
                1 -> http.restEdit(title, updatedSource, compatBase, auth = operatorToken)
                else -> http.actionEdit("pageid" to compat["id"].asText(), updatedSource, compatBase, auth = operatorToken)
            }
            assertThat(response.status).isEqualTo(if (surface == 0) 201 else 200)
            if (surface == 2) assertThat(response.json.at("/edit/oldrevid").asLong()).isEqualTo(compatBase)
            val previous = current
            current = http.get("$native/$pageId", operatorToken).json
            assertThat(current["source"].asText()).isEqualTo(updatedSource)
            assertThat(current["currentRevisionId"]).isNotEqualTo(previous["currentRevisionId"])
            val action = http.actionRead("titles" to title, operatorToken).json.at("/query/pages/0/revisions/0")
            assertThat(action["parentid"].asLong()).isEqualTo(compatBase)
            assertThat(action.at("/slots/main/content").asText()).isEqualTo(updatedSource)
            val state = jdbc.queryForMap(
                """
                SELECT r.parent_revision_id, r.source, r.summary, r.actor, a.event_type,
                       a.actor AS audit_actor, o.actor AS outbox_actor, a.request_id
                FROM wiki_revision r
                JOIN wiki_audit a ON a.page_id = r.page_id AND a.revision_id = r.id
                JOIN wiki_outbox o ON o.page_id = r.page_id AND o.revision_id = r.id
                    AND o.request_id = a.request_id AND o.event_type = a.event_type
                WHERE r.id = ?
                """.trimIndent(),
                UUID.fromString(current["currentRevisionId"].asText()),
            )
            assertThat(state["parent_revision_id"].toString()).isEqualTo(base)
            assertThat(state["source"]).isEqualTo(updatedSource)
            assertThat(state["summary"]).isEqualTo("Edit summary")
            assertThat(state["event_type"]).isEqualTo("revision.committed")
            assertThat(listOf(state["actor"], state["audit_actor"], state["outbox_actor"]))
                .containsOnly("operator")
            assertThat(state["request_id"].toString()).isEqualTo(response.header("X-Request-Id"))
        }
        assertThat(rowCounts()).containsExactly(before[0], before[1] + 3, before[2] + 3, before[3] + 3)
    }

    @Test
    fun `concurrent edit surfaces commit once and invalid or unauthorized writes do not commit`() {
        val title = "Edit-race-${UUID.randomUUID()}"
        val created = http.nativeCreate(title, source).json
        val pageId = created["id"].asText()
        val base = created["currentRevisionId"].asText()
        val compatBase = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        val path = "$native/$pageId/revisions"
        val before = rowCounts()
        fun body(source: String) = mapOf("baseRevisionId" to base, "source" to source)
        val denied = http.postJson(path, body("Anonymous"))
        assertThat(denied.status).isEqualTo(403)
        assertSchema("/paths/~1pages~1{pageId}~1revisions/post/responses/403", denied.json)
        assertThat(http.restEdit(title, "Anonymous", compatBase).status).isEqualTo(403)
        assertThat(http.actionEdit("title" to title, "Anonymous", compatBase).json.at("/error/code").asText())
            .isEqualTo("permissiondenied")
        val invalid = http.postJson(path, body("Bad\u0000source"), operatorToken)
        assertThat(invalid.status).isEqualTo(422)
        assertSchema("/paths/~1pages~1{pageId}~1revisions/post/responses/422", invalid.json)
        assertThat(http.restEdit(title, "Bad\u0000source", compatBase, auth = operatorToken).status).isEqualTo(400)
        assertThat(http.actionEdit("title" to title, "Bad\u0000source", compatBase, auth = operatorToken)
            .json.at("/error/code").asText()).isEqualTo("invalidparam")
        assertThat(rowCounts()).isEqualTo(before)

        val barrier = CyclicBarrier(3)
        val calls = listOf(
            { http.postJson(path, body("Native winner"), operatorToken) },
            { http.restEdit(title, "REST winner", compatBase, auth = operatorToken) },
            { http.actionEdit("title" to title, "Action winner", compatBase, auth = operatorToken) },
        )
        val results = Executors.newFixedThreadPool(calls.size).use { executor ->
            executor.invokeAll(calls.map { call -> Callable { barrier.await(); call() } }).map { it.get() }
        }
        val wins = listOf(results[0].status == 201, results[1].status == 200, results[2].json.has("edit"))
        assertThat(wins.count { it }).isEqualTo(1)
        if (!wins[0]) assertThat(results[0].status).isEqualTo(409)
        if (!wins[1]) assertThat(results[1].status).isEqualTo(409)
        if (!wins[2]) assertThat(results[2].json.at("/error/code").asText()).isEqualTo("editconflict")
        val current = http.get("$native/$pageId", operatorToken).json
        assertThat(current["source"].asText()).isEqualTo(listOf("Native winner", "REST winner", "Action winner")[wins.indexOf(true)])
        if (!wins[0]) {
            assertSchema("/paths/~1pages~1{pageId}~1revisions/post/responses/409", results[0].json)
            assertThat(results[0].json["currentRevisionId"]).isEqualTo(current["currentRevisionId"])
            val expected = http.get("/api/v1/revisions/$base/compare/${current["currentRevisionId"].asText()}", operatorToken)
            assertThat(results[0].json["comparison"]).isEqualTo(expected.json)
        }
        if (!wins[1]) {
            val published = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
            assertThat(results[1].json.at("/to/id").asLong()).isEqualTo(published)
            assertThat(results[1].json["diff"]).isEqualTo(http.restCompare(compatBase, published, operatorToken).json["diff"])
        }
        assertThat(rowCounts()).containsExactly(before[0], before[1] + 1, before[2] + 1, before[3] + 1)
    }

    @Test
    fun `every creation surface persists the same first revision and events`() {
        val nativeTitle = "Élan ${UUID.randomUUID()}"
        val restTitle = "Rest ${UUID.randomUUID()}"
        val actionTitle = "Action ${UUID.randomUUID()}"
        val nativeResult = http.nativeCreate(nativeTitle.replace("Élan ", "e\u0301lan__"), source)
        assertThat(nativeResult.status).isEqualTo(201)
        assertSchema("/paths/~1pages/post/responses/201", nativeResult.json)
        assertThat(nativeResult.header("Location")).isEqualTo("$native/${nativeResult.json["id"].asText()}")

        val restResult = http.restCreate(restTitle, source, auth = operatorToken)
        assertThat(restResult.status).isEqualTo(201)
        val actionResult = http.actionCreate(actionTitle, source, auth = operatorToken)
        assertThat(actionResult.json.at("/edit/result").asText()).isEqualTo("Success")

        for ((title, result) in listOf(nativeTitle to nativeResult, restTitle to restResult, actionTitle to actionResult)) {
            val key = title.replace(' ', '_')
            val byTitle = http.get("$native/by-title/$key", operatorToken)
            assertThat(byTitle.status).isEqualTo(200)
            assertSchema("/paths/~1pages~1by-title~1{title}/get/responses/200", byTitle.json)
            val byId = http.get("$native/${byTitle.json["id"].asText()}", operatorToken)
            assertSchema("/paths/~1pages~1{pageId}/get/responses/200", byId.json)
            assertThat(byId.json).isEqualTo(byTitle.json)
            assertThat(byTitle.json["source"].asText()).isEqualTo(source)
            assertThat(byTitle.json["normalizedTitle"].asText()).isEqualTo(title)

            val rest = http.restRead(key, operatorToken).json
            val action = http.actionRead("pageids" to rest["id"].asText(), operatorToken).json
            assertThat(action.at("/query/pages/0/revisions/0/revid")).isEqualTo(rest.at("/latest/id"))
            assertThat(action.at("/query/pages/0/revisions/0/parentid").asInt()).isZero()
            assertThat(action.at("/query/pages/0/revisions/0/slots/main/content").asText()).isEqualTo(source)
            assertThat(rest["source"].asText()).isEqualTo(source)
            val info = http.actionQuery("prop" to "info", "titles" to key, auth = operatorToken).json
            assertThat(info.at("/query/pages/0/lastrevid")).isEqualTo(rest.at("/latest/id"))
            assertState(title, byTitle.json, result.header("X-Request-Id"))
        }
    }

    @Test
    fun `invalid missing and private requests preserve state across surfaces`() {
        val missing = "Missing_${UUID.randomUUID()}"
        val secret = "Secret_${UUID.randomUUID()}"
        assertThat(http.nativeCreate(secret, source).status).isEqualTo(201)
        val before = rowCounts()

        for (key in listOf(secret, missing)) {
            val denied = http.get("$native/by-title/$key")
            assertThat(denied.status).isEqualTo(403)
            assertSchema("/paths/~1pages~1by-title~1{title}/get/responses/403", denied.json)
            assertThat(http.restRead(key).status).isEqualTo(403)
            assertThat(http.actionRead("titles" to key).json.at("/error/code").asText()).isEqualTo("readapidenied")
        }
        val absent = http.get("$native/by-title/$missing", operatorToken)
        assertThat(absent.status).isEqualTo(404)
        assertSchema("/paths/~1pages~1by-title~1{title}/get/responses/404", absent.json)
        assertThat(http.restRead(missing, operatorToken).status).isEqualTo(404)
        assertThat(http.actionRead("titles" to missing, operatorToken).json.at("/query/pages/0/missing").asBoolean()).isTrue()

        assertNativeError(http.nativeCreate(missing, source, null), 403)
        assertThat(http.restCreate(missing, source).status).isEqualTo(403)
        assertThat(http.actionCreate(missing, source).json.at("/error/code").asText()).isEqualTo("permissiondenied")
        val unauthorized = http.get("$native/by-title/$secret", "incorrect-token")
        assertThat(unauthorized.status).isEqualTo(401)
        assertThat(unauthorized.header("WWW-Authenticate")).isEqualTo("Bearer")
        assertSchema("/paths/~1pages~1by-title~1{title}/get/responses/401", unauthorized.json)
        assertNativeError(http.nativeCreate("[bad]", source), 422)
        assertNativeError(http.postJson(native, mapOf("title" to missing, "source" to source), operatorToken), 400)
        assertNativeError(http.postJson(native, mapOf("title" to missing, "namespaceId" to 0, "source" to true), operatorToken), 400)
        assertNativeError(http.postText(native, source, operatorToken), 415)
        assertNativeError(http.nativeCreate(secret, source), 409)
        assertThat(http.restCreate("[bad]", source, auth = operatorToken).status).isEqualTo(400)
        assertThat(http.actionCreate("[bad]", source, auth = operatorToken).json.at("/error/code").asText()).isEqualTo("invalidtitle")
        assertThat(http.actionCreate(missing, source, "invalid", operatorToken).json.at("/error/code").asText()).isEqualTo("badtoken")
        assertThat(rowCounts()).isEqualTo(before)
    }

    @Test
    fun `encoded title punctuation cannot be mistaken for a path parameter`() {
        val base = "Punctuation-${UUID.randomUUID()}"
        assertThat(http.nativeCreate(base, "base").status).isEqualTo(201)
        assertThat(http.nativeCreate("$base;part", source).status).isEqualTo(201)
        assertThat(http.get("$native/by-title/$base%3Bpart", operatorToken).json["source"].asText()).isEqualTo(source)
        assertThat(http.restRead("$base;part", operatorToken).json["source"].asText()).isEqualTo(source)
        assertThat(http.get("$native/by-title/$base;part", operatorToken).status).isEqualTo(422)
        assertThat(http.get("/w/rest.php/v1/page/$base;part", operatorToken).status).isEqualTo(404)
    }

    @Test
    fun `concurrent normalized creations have a single commit across all surfaces`() {
        val id = UUID.randomUUID()
        val title = "Race $id"
        val before = rowCounts()
        val barrier = CyclicBarrier(3)
        val calls = listOf(
            { http.nativeCreate(title, source) },
            { http.restCreate("race__$id", source, auth = operatorToken) },
            { http.actionCreate(" Race_$id ", source, auth = operatorToken) },
        )
        val results = Executors.newFixedThreadPool(calls.size).use { executor ->
            executor.invokeAll(calls.map { call -> Callable { barrier.await(); call() } }).map { it.get() }
        }
        val wins = listOf(results[0].status == 201, results[1].status == 201, results[2].json.has("edit"))
        assertThat(wins.count { it }).isEqualTo(1)
        if (!wins[0]) assertNativeError(results[0], 409)
        if (!wins[1]) assertThat(results[1].status).isEqualTo(409)
        if (!wins[2]) assertThat(results[2].json.at("/error/code").asText()).isEqualTo("articleexists")
        assertThat(rowCounts()).containsExactlyElementsOf(before.map { it + 1 })
        val page = http.get("$native/by-title/Race_$id", operatorToken).json
        assertState(title, page, results[wins.indexOf(true)].header("X-Request-Id"))
    }

    @Test
    fun `a failure after the audit insert rolls back every write`() {
        val title = "Rollback ${UUID.randomUUID()}"
        val existing = http.nativeCreate("Edit $title", source).json
        val path = "$native/${existing["id"].asText()}"
        val before = rowCounts()
        jdbc.execute("ALTER TABLE wiki_outbox ADD CONSTRAINT contract_reject_outbox CHECK (false) NOT VALID")
        try {
            assertThat(http.nativeCreate(title, source).status).isEqualTo(500)
            assertThat(http.get("$native/by-title/${title.replace(' ', '_')}", operatorToken).status).isEqualTo(404)
            val edit = http.postJson("$path/revisions",
                mapOf("baseRevisionId" to existing["currentRevisionId"].asText(), "source" to "Never committed"),
                operatorToken)
            assertThat(edit.status).isEqualTo(500)
            assertThat(http.get(path, operatorToken).json).isEqualTo(existing)
            assertThat(rowCounts()).isEqualTo(before)
        } finally {
            jdbc.execute("ALTER TABLE wiki_outbox DROP CONSTRAINT contract_reject_outbox")
        }
    }

    @Test
    fun `native undo rejects explicit null base without changing page or records`() {
        val initial = http.nativeCreate("Undo-null-${UUID.randomUUID()}", source).json
        val path = "$native/${initial["id"].asText()}"
        val edited = http.postJson("$path/revisions", mapOf(
            "baseRevisionId" to initial["currentRevisionId"].asText(), "source" to "Changed",
        ), operatorToken)
        assertThat(edited.status).isEqualTo(201)
        val head = edited.json["currentRevisionId"].asText()
        val body = mapOf("revisionId" to head, "baseRevisionId" to null)
        val contract = spec.at("$undoOperation/requestBody/content/application~1json/schema")
        validateSchema(contract, yaml.valueToTree(body - "baseRevisionId"))
        val schema = yaml.createObjectNode()
        schema.set<JsonNode>("components", spec["components"])
        schema.set<JsonNode>("allOf", yaml.createArrayNode().add(contract))
        assertThat(schemaFactory.getSchema(schema).validate(yaml.valueToTree(body))).isNotEmpty()
        val before = rowCounts()

        val response = http.postJson("$path/undos", body, operatorToken)
        assertThat(response.status).isEqualTo(400)
        assertThat(response.json["code"].asText()).isEqualTo("INVALID_BODY")
        assertSchema("$undoOperation/responses/400", response.json)
        assertThat(http.get(path, operatorToken).json["currentRevisionId"].asText()).isEqualTo(head)
        assertThat(rowCounts()).isEqualTo(before)
    }

    @Test
    fun `both undo surfaces append restored source with one audit and outbox record`() {
        val title = "Undo-${UUID.randomUUID()}"
        val initial = http.nativeCreate(title, source).json
        val pageId = initial["id"].asText()
        val parent = initial["currentRevisionId"].asText()
        val path = "$native/$pageId"
        val edit = http.postJson("$path/revisions", mapOf("baseRevisionId" to parent, "source" to "Changed"), operatorToken)
        assertThat(edit.status).isEqualTo(201)
        val target = edit.json["currentRevisionId"].asText()
        val compat = http.restRead(title, operatorToken).json
        val compatTarget = compat.at("/latest/id").asLong()
        val before = rowCounts()
        val body = mapOf("revisionId" to target, "summary" to "Restore source")
        validateSchema(spec.at("$undoOperation/requestBody/content/application~1json/schema"), yaml.valueToTree(body))
        val nativeUndo = http.postJson("$path/undos", body, operatorToken)
        assertThat(nativeUndo.status).isEqualTo(201)
        assertSchema("$undoOperation/responses/201", nativeUndo.json)
        assertThat(nativeUndo.json["source"].asText()).isEqualTo(source)
        val restored = nativeUndo.json["currentRevisionId"].asText()
        val compatRestored = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        assertThat(compatRestored).isGreaterThan(compatTarget)
        val actionUndo = http.actionUndo("pageid" to compat["id"].asText(), compatRestored, operatorToken, "Undo restoration")
        assertThat(actionUndo.json.at("/edit/result").asText()).isEqualTo("Success")
        assertThat(actionUndo.json.at("/edit/oldrevid").asLong()).isEqualTo(compatRestored)
        val current = http.get(path, operatorToken).json
        val currentId = current["currentRevisionId"].asText()
        assertThat(current["source"].asText()).isEqualTo("Changed")
        assertThat(http.restRead(title, operatorToken).json.at("/latest/id").asLong())
            .isEqualTo(actionUndo.json.at("/edit/newrevid").asLong())
        val history = http.get("$path/revisions", operatorToken).json["revisions"]
        assertThat(history.map { it["id"].asText() }).containsExactly(currentId, restored, target, parent)
        assertThat(history.take(3).map { it["parentRevisionId"].asText() }).containsExactly(restored, target, parent)
        assertThat(history.take(2).map { it["summary"].asText() }).containsExactly("Undo restoration", "Restore source")
        assertThat(http.get("/api/v1/revisions/$parent", operatorToken).json["source"].asText()).isEqualTo(source)
        assertThat(http.get("/api/v1/revisions/$target", operatorToken).json["source"].asText()).isEqualTo("Changed")
        for ((id, request) in listOf(restored to nativeUndo, currentId to actionUndo)) {
            val row = jdbc.queryForMap(
                """
                SELECT a.event_type, a.request_id, a.actor, o.actor AS outbox_actor
                FROM wiki_audit a JOIN wiki_outbox o ON a.page_id = o.page_id AND a.revision_id = o.revision_id
                    AND a.request_id = o.request_id AND a.event_type = o.event_type
                WHERE a.revision_id = ?
                """.trimIndent(), UUID.fromString(id),
            )
            assertThat(row["event_type"]).isEqualTo("revision.committed")
            assertThat(row["request_id"].toString()).isEqualTo(request.header("X-Request-Id"))
            assertThat(listOf(row["actor"], row["outbox_actor"])).containsOnly("operator")
        }
        assertThat(rowCounts()).containsExactly(before[0], before[1] + 2, before[2] + 2, before[3] + 2)
    }

    @Test
    fun `undo rejects first foreign missing and stale targets and rolls back failed outbox writes`() {
        val title = "Undo-rejections-${UUID.randomUUID()}"
        val initial = http.nativeCreate(title, source).json
        val first = initial["currentRevisionId"].asText()
        val firstCompat = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        val path = "$native/${initial["id"].asText()}"
        fun undo(id: String) = http.postJson("$path/undos", mapOf("revisionId" to id), operatorToken)
        fun rejected(id: String, status: Int, code: String) {
            val response = undo(id)
            assertThat(response.status).isEqualTo(status)
            assertThat(response.json["code"].asText()).isEqualTo(code)
            assertSchema("$undoOperation/responses/$status", response.json)
        }
        fun actionUndo(id: Long) = http.actionUndo("title" to title, id, operatorToken)
        val beforeFirst = rowCounts()
        rejected(first, 409, "UNDO_NO_PARENT")
        assertThat(actionUndo(firstCompat).json.at("/error/code").asText()).isEqualTo("nosuchrevid")
        assertThat(rowCounts()).isEqualTo(beforeFirst)

        val foreign = "Undo-foreign-${UUID.randomUUID()}"
        http.nativeCreate(foreign, "Another page")
        val otherFirst = http.restRead(foreign, operatorToken).json.at("/latest/id").asLong()
        val otherHead = http.restEdit(foreign, "Another edit", otherFirst, auth = operatorToken).json.at("/latest/id").asLong()
        val foreignId = http.get("$native/by-title/$foreign", operatorToken).json["currentRevisionId"].asText()
        val edited = http.postJson("$path/revisions", mapOf("baseRevisionId" to first, "source" to "Keep"), operatorToken).json
        val head = edited["currentRevisionId"].asText()
        val compatHead = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        val before = rowCounts()
        rejected(foreignId, 404, "REVISION_NOT_FOUND")
        rejected(UUID.randomUUID().toString(), 404, "REVISION_NOT_FOUND")
        rejected(first, 409, "UNDO_NO_PARENT")
        assertThat(actionUndo(otherHead).json.at("/error/code").asText()).isEqualTo("revwrongpage")
        assertThat(actionUndo(Long.MAX_VALUE).json.at("/error/code").asText()).isEqualTo("nosuchrevid")
        assertThat(actionUndo(firstCompat).json.at("/error/code").asText()).isEqualTo("nosuchrevid")
        val badToken = http.actionUndo("title" to title, compatHead, operatorToken, token = "invalid")
        assertThat(badToken.json.at("/error/code").asText()).isEqualTo("badtoken")

        jdbc.execute("ALTER TABLE wiki_outbox ADD CONSTRAINT contract_reject_undo_outbox CHECK (false) NOT VALID")
        try {
            assertThat(undo(head).status).isEqualTo(500)
            assertThat(actionUndo(compatHead).status).isEqualTo(500)
        } finally {
            jdbc.execute("ALTER TABLE wiki_outbox DROP CONSTRAINT contract_reject_undo_outbox")
        }
        assertThat(http.get(path, operatorToken).json).isEqualTo(edited)
        assertThat(rowCounts()).isEqualTo(before)
    }

    @Test
    fun `historical undo preserves disjoint changes and rejects overlapping changes on both surfaces`() {
        for (action in listOf(false, true)) {
            val title = "Undo-history-${UUID.randomUUID()}"
            val initial = http.nativeCreate(title, "one\ntwo\nthree\nfour\nfive\n").json
            val path = "$native/${initial["id"].asText()}"
            val target = http.postJson("$path/revisions", mapOf(
                "baseRevisionId" to initial["currentRevisionId"].asText(),
                "source" to "ONE\ntwo\nthree\nfour\nfive\n"), operatorToken).json
            val compatTarget = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
            val latest = http.postJson("$path/revisions", mapOf(
                "baseRevisionId" to target["currentRevisionId"].asText(),
                "source" to "ONE\ntwo\nthree\nfour\nFIVE\n"), operatorToken).json
            val compatLatest = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
            val before = rowCounts()
            if (action) {
                val response = http.actionUndo("title" to title, compatTarget, operatorToken, base = compatLatest)
                assertThat(response.json.at("/edit/result").asText()).isEqualTo("Success")
                assertThat(response.json.at("/edit/oldrevid").asLong()).isEqualTo(compatLatest)
            } else {
                val body = mapOf("revisionId" to target["currentRevisionId"].asText(),
                    "baseRevisionId" to latest["currentRevisionId"].asText())
                validateSchema(spec.at("$undoOperation/requestBody/content/application~1json/schema"), yaml.valueToTree(body))
                val response = http.postJson("$path/undos", body, operatorToken)
                assertThat(response.status).isEqualTo(201)
                assertSchema("$undoOperation/responses/201", response.json)
            }
            val current = http.get(path, operatorToken).json
            assertThat(current["source"].asText()).isEqualTo("one\ntwo\nthree\nfour\nFIVE\n")
            assertThat(rowCounts()).containsExactly(before[0], before[1] + 1, before[2] + 1, before[3] + 1)
            val revision = current["currentRevisionId"].asText()
            val records = checkNotNull(jdbc.queryForObject("""
                SELECT count(*) FROM wiki_revision r JOIN wiki_audit a ON a.revision_id = r.id
                JOIN wiki_outbox o ON o.revision_id = r.id AND o.request_id = a.request_id
                WHERE r.id = ? AND r.parent_revision_id = ?
            """.trimIndent(), Long::class.javaObjectType, UUID.fromString(revision),
                UUID.fromString(latest["currentRevisionId"].asText())))
            assertThat(records).isEqualTo(1L)

            val overlap = http.postJson("$path/revisions", mapOf("baseRevisionId" to revision,
                "source" to "CONFLICT\ntwo\nthree\nfour\nFIVE\n"), operatorToken).json
            val overlapCompat = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
            val unchanged = rowCounts()
            val nativeConflict = http.postJson("$path/undos", mapOf("revisionId" to target["currentRevisionId"].asText()), operatorToken)
            assertThat(nativeConflict.status).isEqualTo(409)
            assertThat(nativeConflict.json["code"].asText()).isEqualTo("EDIT_CONFLICT")
            assertSchema("$undoOperation/responses/409", nativeConflict.json)
            val actionConflict = http.actionUndo("title" to title, compatTarget, operatorToken, base = overlapCompat)
            assertThat(actionConflict.json.at("/error/code").asText()).isEqualTo("undofailure")
            assertThat(http.get(path, operatorToken).json).isEqualTo(overlap)
            assertThat(rowCounts()).isEqualTo(unchanged)
        }
    }

    @Test
    fun `native historical undo handles paragraph gaps and rejects adjacent edits`() {
        for (adjacent in listOf(false, true)) {
            val title = "Undo-boundary-${UUID.randomUUID()}"
            val parent = if (adjacent) "old\nb" else "Intro\n\nOld para\n\nEnd"
            val targetSource = if (adjacent) "new\nb" else "Intro\n\nNew para\n\nEnd"
            val headSource = if (adjacent) "new\nB" else "Intro\n\nNew para\n\nEnd more"
            val initial = http.nativeCreate(title, parent).json
            val path = "$native/${initial["id"].asText()}"
            val target = http.postJson("$path/revisions", mapOf("baseRevisionId" to initial["currentRevisionId"].asText(),
                "source" to targetSource), operatorToken).json
            val head = http.postJson("$path/revisions", mapOf("baseRevisionId" to target["currentRevisionId"].asText(),
                "source" to headSource), operatorToken).json
            val before = rowCounts()
            val result = http.postJson("$path/undos", mapOf("revisionId" to target["currentRevisionId"].asText()), operatorToken)
            if (adjacent) {
                assertThat(result.status).isEqualTo(409)
                assertThat(result.json["code"].asText()).isEqualTo("EDIT_CONFLICT")
                assertThat(http.get(path, operatorToken).json).isEqualTo(head)
                assertThat(rowCounts()).isEqualTo(before)
            } else {
                assertThat(result.status).isEqualTo(201)
                assertThat(result.json["source"].asText()).isEqualTo("Intro\n\nOld para\n\nEnd more")
                assertThat(rowCounts()).containsExactly(before[0], before[1] + 1, before[2] + 1, before[3] + 1)
            }
        }
    }

    @Test
    fun `ambiguous historical undo is rejected without writes on both surfaces`() {
        val title = "Undo-ambiguous-${UUID.randomUUID()}"
        val initial = http.nativeCreate(title, "old\nrepeat\nrepeat\nlast\n").json
        val path = "$native/${initial["id"].asText()}"
        val target = http.postJson("$path/revisions", mapOf("baseRevisionId" to initial["currentRevisionId"].asText(),
            "source" to "repeat\nrepeat\nrepeat\nlast\n"), operatorToken).json
        val compatTarget = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        val latest = http.postJson("$path/revisions", mapOf("baseRevisionId" to target["currentRevisionId"].asText(),
            "source" to "repeat\nrepeat\nrepeat\nLAST\n"), operatorToken).json
        val before = rowCounts()
        val native = http.postJson("$path/undos", mapOf("revisionId" to target["currentRevisionId"].asText()), operatorToken)
        assertThat(native.status).isEqualTo(409)
        assertThat(native.json["code"].asText()).isEqualTo("EDIT_CONFLICT")
        val action = http.actionUndo("title" to title, compatTarget, operatorToken)
        assertThat(action.json.at("/error/code").asText()).isEqualTo("undofailure")
        assertThat(http.get(path, operatorToken).json).isEqualTo(latest)
        assertThat(rowCounts()).isEqualTo(before)
    }

    @Test
    fun `historical undo processing limit fails without writes`() {
        for (lineCount in listOf(1414, 4001)) {
            val title = "Undo-limit-${UUID.randomUUID()}"
            val lines = (1..lineCount).joinToString("\n") { "line $it" }
            val initial = http.nativeCreate(title, "old\n$lines").json
            val path = "$native/${initial["id"].asText()}"
            val target = http.postJson("$path/revisions", mapOf("baseRevisionId" to initial["currentRevisionId"].asText(),
                "source" to "new\n$lines"), operatorToken).json
            val compatTarget = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
            val head = http.postJson("$path/revisions", mapOf("baseRevisionId" to target["currentRevisionId"].asText(),
                "source" to "new\n$lines\nlast"), operatorToken).json
            val before = rowCounts()
            val native = http.postJson("$path/undos", mapOf("revisionId" to target["currentRevisionId"].asText()), operatorToken)
            assertThat(native.status).isEqualTo(422)
            assertThat(native.json["code"].asText()).isEqualTo("UNDO_TOO_LARGE")
            assertSchema("$undoOperation/responses/422", native.json)
            assertThat(http.actionUndo("title" to title, compatTarget, operatorToken).json.at("/error/code").asText()).isEqualTo("undofailure")
            assertThat(http.get(path, operatorToken).json).isEqualTo(head)
            assertThat(rowCounts()).isEqualTo(before)
            }
    }

    @Test
    fun `historical undo rolls back revision and audit if outbox insert fails`() {
        val title = "Undo-history-rollback-${UUID.randomUUID()}"
        val initial = http.nativeCreate(title, "a\nb\nc\nd\ne\n").json
        val path = "$native/${initial["id"].asText()}"
        val target = http.postJson("$path/revisions", mapOf("baseRevisionId" to initial["currentRevisionId"].asText(),
            "source" to "A\nb\nc\nd\ne\n"), operatorToken).json
        val compatTarget = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        val latest = http.postJson("$path/revisions", mapOf("baseRevisionId" to target["currentRevisionId"].asText(),
            "source" to "A\nb\nc\nd\nE\n"), operatorToken).json
        val before = rowCounts()
        jdbc.execute("ALTER TABLE wiki_outbox ADD CONSTRAINT contract_reject_history_undo CHECK (false) NOT VALID")
        try {
            assertThat(http.postJson("$path/undos", mapOf("revisionId" to target["currentRevisionId"].asText()), operatorToken).status).isEqualTo(500)
            assertThat(http.actionUndo("title" to title, compatTarget, operatorToken).status).isEqualTo(500)
        } finally {
            jdbc.execute("ALTER TABLE wiki_outbox DROP CONSTRAINT contract_reject_history_undo")
        }
        assertThat(http.get(path, operatorToken).json).isEqualTo(latest)
        assertThat(rowCounts()).isEqualTo(before)
    }

    @Test
    fun `native and action undo race a normal edit under the same page lock`() {
        val title = "Undo-race-${UUID.randomUUID()}"
        val initial = http.nativeCreate(title, source).json
        val path = "$native/${initial["id"].asText()}"
        val updated = http.postJson("$path/revisions",
            mapOf("baseRevisionId" to initial["currentRevisionId"].asText(), "source" to "Changed"), operatorToken).json
        val target = updated["currentRevisionId"].asText()
        val compatTarget = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        val before = rowCounts()
        val calls = listOf(
            { http.postJson("$path/undos", mapOf("revisionId" to target, "baseRevisionId" to target), operatorToken) },
            { http.actionUndo("title" to title, compatTarget, operatorToken, base = compatTarget) },
            { http.restEdit(title, "Competing edit", compatTarget, auth = operatorToken) },
        )
        val barrier = CyclicBarrier(calls.size)
        val (nativeUndo, actionUndo, edit) = Executors.newFixedThreadPool(calls.size).use { executor ->
            executor.invokeAll(calls.map { call -> Callable { barrier.await(); call() } }).map { it.get() }
        }
        val wins = listOf(nativeUndo.status == 201, actionUndo.json.has("edit"), edit.status == 200)
        assertThat(wins.count { it }).isEqualTo(1)
        if (!wins[0]) assertThat(nativeUndo.json["code"].asText()).isEqualTo("EDIT_CONFLICT")
        if (!wins[1]) assertThat(actionUndo.json.at("/error/code").asText()).isEqualTo("editconflict")
        if (!wins[2]) assertThat(edit.status).isEqualTo(409)
        assertThat(http.get(path, operatorToken).json["source"].asText())
            .isEqualTo(if (wins[2]) "Competing edit" else source)
        assertThat(rowCounts()).containsExactly(before[0], before[1] + 1, before[2] + 1, before[3] + 1)
    }

    private fun assertState(title: String, nativePage: JsonNode, requestId: String) {
        val rows = jdbc.queryForList(
            """
            SELECT p.id, r.id AS revision_id, r.parent_revision_id, r.actor, r.source,
                   a.actor AS audit_actor, o.actor AS outbox_actor, a.request_id, o.published_at,
                   a.event_type
            FROM wiki_page p
            JOIN wiki_revision r ON r.page_id = p.id
            JOIN wiki_audit a ON a.page_id = p.id AND a.revision_id = r.id
            JOIN wiki_outbox o ON o.page_id = p.id AND o.revision_id = r.id
                              AND o.request_id = a.request_id AND o.event_type = a.event_type
            WHERE p.normalized_title = ? AND p.current_revision_id = r.id
            """.trimIndent(),
            title,
        )
        val row = rows.single()
        assertThat(row["id"].toString()).isEqualTo(nativePage["id"].asText())
        assertThat(row["revision_id"].toString()).isEqualTo(nativePage["currentRevisionId"].asText())
        assertThat(row["parent_revision_id"]).isNull()
        assertThat(row["source"]).isEqualTo(source)
        assertThat(row["actor"]).isEqualTo("operator")
        assertThat(row["audit_actor"]).isEqualTo(row["actor"])
        assertThat(row["outbox_actor"]).isEqualTo(row["actor"])
        assertThat(row["event_type"]).isEqualTo("page.created")
        assertThat(row["request_id"].toString()).isEqualTo(requestId)
        assertThat(row["published_at"]).isNull()
    }

    private fun rowCounts() = listOf("wiki_page", "wiki_revision", "wiki_audit", "wiki_outbox").map { table ->
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.javaObjectType))
    }

    private fun assertSchema(pointer: String, body: JsonNode) {
        val response = spec.at(pointer)
        val resolved = if (response.has("\$ref")) spec.at(response["\$ref"].asText().removePrefix("#")) else response
        validateSchema(resolved.at("/content/application~1json/schema"), body)
    }

    private fun validateSchema(contract: JsonNode, body: JsonNode) {
        assertThat(contract.isObject).isTrue()
        val schema = yaml.createObjectNode()
        schema.set<JsonNode>("components", spec["components"])
        schema.set<JsonNode>("allOf", yaml.createArrayNode().add(contract))
        assertThat(schemaFactory.getSchema(schema).validate(body)).isEmpty()
    }

    private fun assertNativeError(response: ContractHttp.Result, status: Int) {
        assertThat(response.status).isEqualTo(status)
        assertSchema("/paths/~1pages/post/responses/$status", response.json)
        assertThat(response.json["requestId"].asText()).isEqualTo(response.header("X-Request-Id"))
    }

    companion object {
        private const val undoOperation = "/paths/~1pages~1{pageId}~1undos/post"
        private val yaml = ObjectMapper(YAMLFactory())
        private val spec = yaml.readTree(File("wiki_design/specs/openapi.yaml"))
        private val schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

        @JvmStatic
        @DynamicPropertySource
        fun database(properties: DynamicPropertyRegistry) = postgresProperties(properties)
    }
}
