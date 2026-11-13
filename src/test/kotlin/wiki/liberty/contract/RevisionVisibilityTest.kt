// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@Tag("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "wiki.pages.anonymous-read=true",
    "wiki.pages.anonymous-edit=true",
    "wiki.http.operator-token=$operatorToken",
])
class RevisionVisibilityTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `each source restriction is enforced by every comparison and source route`() {
        val http = ContractHttp("http://127.0.0.1:$port")
        val title = "Restricted-${UUID.randomUUID()}"
        val secret = "== Private heading ==\nSecret source"
        val created = http.nativeCreate(title, secret).json
        val pageId = created["id"].asText()
        val restricted = created["currentRevisionId"].asText()
        val first = http.restRead(title).json.at("/latest/id").asLong()
        val edited = http.postJson("/api/v1/pages/$pageId/revisions",
            mapOf("baseRevisionId" to restricted, "source" to "Visible source"), operatorToken)
        assertThat(edited.status).isEqualTo(201)
        val visible = edited.json["currentRevisionId"].asText()
        val second = http.restRead(title).json.at("/latest/id").asLong()
        assertThat(first).isPositive()
        assertThat(second).isGreaterThan(first)
        jdbc.update("INSERT INTO wiki_revision_source_restriction (revision_id) VALUES (?)", UUID.fromString(restricted))
        val before = counts()

        for ((from, to, side) in listOf(Triple(restricted, visible, "from"), Triple(visible, restricted, "to"))) {
            val path = "/api/v1/revisions/$from/compare/$to"
            val denied = http.get(path)
            assertThat(denied.status).isEqualTo(403)
            assertThat(denied.json["code"].asText()).isEqualTo("REVISION_SOURCE_HIDDEN")
            assertThat(denied.json.toString()).doesNotContain("Private heading", "Secret source", "Visible source")
            assertThat(http.get(path, operatorToken).status).isEqualTo(200)

            val fromId = if (side == "from") first else second
            val toId = if (side == "from") second else first
            val rest = http.restCompare(fromId, toId)
            assertThat(rest.status).isEqualTo(403)
            assertThat(rest.json["errorKey"].asText()).isEqualTo("rest-compare-inaccessible")
            assertThat(rest.json.at("/messageTranslations/en").asText()).contains("$side parameter")
            assertThat(rest.json.toString()).doesNotContain("Private heading", "Secret source", "Visible source")
            assertThat(http.restCompare(fromId, toId, operatorToken).status).isEqualTo(200)

            val action = http.actionCompare(fromId, toId)
            assertThat(action.status).isEqualTo(200)
            assertThat(action.json.at("/error/code").asText()).isEqualTo("missingcontent")
            assertThat(action.json.toString()).doesNotContain("Private heading", "Secret source", "Visible source")
            assertThat(http.actionCompare(fromId, toId, operatorToken).json.at("/compare/${side}texthidden").asBoolean()).isTrue()
        }

        assertThat(http.get("/api/v1/revisions/$restricted").status).isEqualTo(403)
        assertThat(http.restRevision(first).status).isEqualTo(403)
        assertThat(http.actionRead("revids" to "$first").json.at("/error/code").asText()).isEqualTo("missingcontent")
        val history = http.restHistory(title).json["revisions"]
        assertThat(history[0]["delta"].isNull).isTrue()
        assertThat(history[1].has("size")).isFalse()
        assertThat(http.restRead(title).json["source"].asText()).isEqualTo("Visible source")
        assertThat(counts()).isEqualTo(before)

        val hiddenBase = http.postJson("/api/v1/pages/$pageId/revisions",
            mapOf("baseRevisionId" to restricted, "source" to "Draft secret"))
        assertThat(hiddenBase.status).isEqualTo(409)
        assertThat(hiddenBase.json["currentRevisionId"].asText()).isEqualTo(visible)
        assertThat(hiddenBase.json.has("comparison")).isFalse()
        assertThat(hiddenBase.json.toString()).doesNotContain("Secret source", "Visible source", "Draft secret")
        val hiddenRest = http.restEdit(title, "Draft secret", first)
        assertThat(hiddenRest.status).isEqualTo(409)
        assertThat(hiddenRest.json.has("diff")).isFalse()
        assertThat(hiddenRest.json.toString()).doesNotContain("Secret source", "Visible source", "Draft secret")
        val hiddenAction = http.actionEdit("title" to title, "Draft secret", first)
        assertThat(hiddenAction.json.at("/error/code").asText()).isEqualTo("editconflict")
        assertThat(hiddenAction.json.toString()).doesNotContain("Secret source", "Visible source", "Draft secret")
        assertThat(counts()).isEqualTo(before)

        jdbc.update("INSERT INTO wiki_revision_source_restriction (revision_id) VALUES (?)", UUID.fromString(visible))
        assertThat(http.get("/api/v1/pages/$pageId").status).isEqualTo(403)
        assertThat(http.restRead(title).status).isEqualTo(403)
        assertThat(http.postJson("/api/v1/pages/$pageId/revisions",
            mapOf("baseRevisionId" to visible, "source" to "Attempted edit")).status).isEqualTo(403)
        val deniedRest = http.restEdit(title, "Draft secret", first)
        assertThat(deniedRest.status).isEqualTo(403)
        assertThat(deniedRest.json.toString()).doesNotContain("Secret source", "Visible source", "Draft secret")
        val deniedAction = http.actionEdit("title" to title, "Draft secret", first)
        assertThat(deniedAction.json.toString()).doesNotContain("Secret source", "Visible source", "Draft secret")
        val operatorConflict = http.postJson("/api/v1/pages/$pageId/revisions",
            mapOf("baseRevisionId" to restricted, "source" to "Draft secret"), operatorToken)
        assertThat(operatorConflict.status).isEqualTo(409)
        assertThat(operatorConflict.json.has("currentRevisionId")).isFalse()
        assertThat(operatorConflict.json.has("comparison")).isFalse()
        assertThat(http.get("/api/v1/pages/$pageId", operatorToken).json).isEqualTo(edited.json)
        assertThat(counts()).containsExactlyElementsOf(before.dropLast(1) + (before.last()!! + 1))
    }

    @Test
    fun `undo cannot publish restricted parent source and anonymous callers cannot undo a restricted head`() {
        val http = ContractHttp("http://127.0.0.1:$port")
        val title = "Undo-restricted-${UUID.randomUUID()}"
        val page = http.nativeCreate(title, "Parent source").json
        val path = "/api/v1/pages/${page["id"].asText()}"
        val parent = page["currentRevisionId"].asText()
        val edited = http.postJson("$path/revisions",
            mapOf("baseRevisionId" to parent, "source" to "New source"), operatorToken).json
        val head = edited["currentRevisionId"].asText()
        val compat = http.restRead(title).json.at("/latest/id").asLong()
        fun restrict(id: String) = jdbc.update(
            "INSERT INTO wiki_revision_source_restriction (revision_id) VALUES (?)", UUID.fromString(id),
        )
        fun denied(auth: String?) {
            val native = http.postJson("$path/undos", mapOf("revisionId" to head), auth)
            assertThat(native.status).isEqualTo(403)
            assertThat(native.json["code"].asText()).isEqualTo("REVISION_SOURCE_HIDDEN")
            val action = http.actionUndo("title" to title, compat, auth)
            assertThat(action.json.at("/error/code").asText()).isEqualTo("missingcontent")
            assertThat(listOf(native.json.toString(), action.json.toString())).noneMatch {
                it.contains("Parent source") || it.contains("New source")
            }
        }

        restrict(parent)
        val restrictedParentState = counts()
        denied(null)
        denied(operatorToken)
        assertThat(counts()).isEqualTo(restrictedParentState)
        assertThat(http.get(path, operatorToken).json).isEqualTo(edited)

        jdbc.update("DELETE FROM wiki_revision_source_restriction WHERE revision_id = ?", UUID.fromString(parent))
        restrict(head)
        val restrictedHeadState = counts()
        denied(null)
        assertThat(counts()).isEqualTo(restrictedHeadState)
        assertThat(http.get(path, operatorToken).json).isEqualTo(edited)
        val restored = http.postJson("$path/undos", mapOf("revisionId" to head), operatorToken)
        assertThat(restored.status).isEqualTo(201)
        assertThat(http.get(path).json["source"].asText()).isEqualTo("Parent source")
        assertThat(http.get("/api/v1/revisions/$head").status).isEqualTo(403)
    }

    @Test
    fun `anonymous historical undo rejects restricted target or parent without writes`() {
        val http = ContractHttp("http://127.0.0.1:$port")
        val title = "Undo-hidden-target-${UUID.randomUUID()}"
        val initial = http.nativeCreate(title, "one\ntwo\nthree\nfour\nfive").json
        val path = "/api/v1/pages/${initial["id"].asText()}"
        val parent = initial["currentRevisionId"].asText()
        val target = http.postJson("$path/revisions", mapOf("baseRevisionId" to parent,
            "source" to "ONE\ntwo\nthree\nfour\nfive"), operatorToken).json
        val targetId = target["currentRevisionId"].asText()
        val compatTarget = http.restRead(title).json.at("/latest/id").asLong()
        val head = http.postJson("$path/revisions", mapOf("baseRevisionId" to targetId,
            "source" to "ONE\ntwo\nthree\nfour\nFIVE"), operatorToken).json
        for (restricted in listOf(parent, targetId)) {
            jdbc.update("INSERT INTO wiki_revision_source_restriction (revision_id) VALUES (?)", UUID.fromString(restricted))
            val before = counts()
            val native = http.postJson("$path/undos", mapOf("revisionId" to targetId))
            assertThat(native.status).isEqualTo(403)
            assertThat(native.json["code"].asText()).isEqualTo("REVISION_SOURCE_HIDDEN")
            val action = http.actionUndo("title" to title, compatTarget)
            assertThat(action.json.at("/error/code").asText()).isEqualTo("missingcontent")
            assertThat(counts()).isEqualTo(before)
            assertThat(http.get(path, operatorToken).json).isEqualTo(head)
            jdbc.update("DELETE FROM wiki_revision_source_restriction WHERE revision_id = ?", UUID.fromString(restricted))
        }
    }

    @Test
    fun `historical undo cannot publish restricted current source even for operator`() {
        val http = ContractHttp("http://127.0.0.1:$port")
        val title = "Undo-hidden-history-${UUID.randomUUID()}"
        val page = http.nativeCreate(title, "one\ntwo\nthree\nfour\nfive\n").json
        val path = "/api/v1/pages/${page["id"].asText()}"
        val target = http.postJson("$path/revisions", mapOf("baseRevisionId" to page["currentRevisionId"].asText(),
            "source" to "ONE\ntwo\nthree\nfour\nfive\n"), operatorToken).json
        val targetId = target["currentRevisionId"].asText()
        val compatTarget = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        val head = http.postJson("$path/revisions", mapOf("baseRevisionId" to targetId,
            "source" to "ONE\ntwo\nthree\nfour\nSECRET\n"), operatorToken).json
        val headId = head["currentRevisionId"].asText()
        val compatHead = http.restRead(title, operatorToken).json.at("/latest/id").asLong()
        jdbc.update("INSERT INTO wiki_revision_source_restriction (revision_id) VALUES (?)", UUID.fromString(headId))
        val before = counts()
        for (explicitBase in listOf(false, true)) {
            val body = mutableMapOf("revisionId" to targetId)
            if (explicitBase) body["baseRevisionId"] = headId
            val native = http.postJson("$path/undos", body, operatorToken)
            assertThat(native.status).isEqualTo(403)
            assertThat(native.json["code"].asText()).isEqualTo("REVISION_SOURCE_HIDDEN")
            val action = http.actionUndo("title" to title, compatTarget, operatorToken,
                base = if (explicitBase) compatHead else null)
            assertThat(action.json.at("/error/code").asText()).isEqualTo("missingcontent")
            assertThat(listOf(native.json.toString(), action.json.toString())).noneMatch { it.contains("SECRET") }
            assertThat(counts()).isEqualTo(before)
            assertThat(http.get(path, operatorToken).json).isEqualTo(head)
        }
    }

    @Test
    fun `historical undo does not disclose a restricted current revision on stale base`() {
        val http = ContractHttp("http://127.0.0.1:$port")
        val title = "Undo-hidden-current-${UUID.randomUUID()}"
        val page = http.nativeCreate(title, "one\ntwo\nthree\nfour\nfive\n").json
        val path = "/api/v1/pages/${page["id"].asText()}"
        val target = http.postJson("$path/revisions", mapOf("baseRevisionId" to page["currentRevisionId"].asText(),
            "source" to "ONE\ntwo\nthree\nfour\nfive\n"), operatorToken).json
        val compatTarget = http.restRead(title).json.at("/latest/id").asLong()
        val head = http.postJson("$path/revisions", mapOf("baseRevisionId" to target["currentRevisionId"].asText(),
            "source" to "ONE\ntwo\nthree\nfour\nSECRET\n"), operatorToken).json
        val headId = head["currentRevisionId"].asText()
        val compatHead = http.restRead(title).json.at("/latest/id").asLong()
        jdbc.update("INSERT INTO wiki_revision_source_restriction (revision_id) VALUES (?)", UUID.fromString(headId))
        val before = counts()
        val native = http.postJson("$path/undos", mapOf("revisionId" to target["currentRevisionId"].asText(),
            "baseRevisionId" to target["currentRevisionId"].asText()), operatorToken)
        assertThat(native.status).isEqualTo(409)
        assertThat(native.json["code"].asText()).isEqualTo("EDIT_CONFLICT")
        assertThat(native.json.toString()).doesNotContain(headId, "SECRET")
        val action = http.actionUndo("title" to title, compatTarget, base = compatTarget)
        assertThat(action.json.toString()).doesNotContain(compatHead.toString(), "SECRET")
        assertThat(counts()).isEqualTo(before)
    }

    private fun counts() = listOf("wiki_page", "wiki_revision", "wiki_audit", "wiki_outbox", "wiki_revision_source_restriction")
        .map { jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.javaObjectType) }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(properties: DynamicPropertyRegistry) = postgresProperties(properties)
    }
}
