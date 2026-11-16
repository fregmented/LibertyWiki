// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.contract

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Tag("mediawiki")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "wiki.pages.anonymous-read=true",
    "wiki.pages.anonymous-edit=true",
    "wiki.pages.anonymous-create=true",
])
class MediaWikiPageContractTest {
    @LocalServerPort private var port: Int = 0
    private val source = "== Example ==\nRaw [[Wikitext]]"

    @Test
    fun `compares documented creation and source contracts against MediaWiki 1_46_2`() {
        val baseline = baseline()
        val local = ContractHttp("http://127.0.0.1:$port")
        val suffix = UUID.randomUUID().toString()
        val actionTitle = "Action $suffix"
        val restTitle = "Rest $suffix"
        val missingTitle = "Missing $suffix"
        assertThat(observe(local, actionTitle, restTitle, missingTitle))
            .isEqualTo(observe(baseline, actionTitle, restTitle, missingTitle))
    }

    @Test
    fun `compares successful existing page updates and overlapping edits against MediaWiki 1_46_2`() {
        val baseline = baseline()
        val local = ContractHttp("http://127.0.0.1:$port")
        val title = "Edit_${UUID.randomUUID()}"
        assertThat(observeEdit(local, title, includeConflictDiff = true))
            .isEqualTo(observeEdit(baseline, title, includeConflictDiff = false))
    }

    @Test
    fun `compares revision history and old source against MediaWiki 1_46_2`() {
        val baseline = baseline()
        val local = ContractHttp("http://127.0.0.1:$port")
        val title = "History_${UUID.randomUUID()}"
        assertThat(observeHistory(local, title)).isEqualTo(observeHistory(baseline, title))
    }

    @Test
    fun `compares undo of latest edit against MediaWiki 1_46_2`() {
        val baseline = baseline()
        val local = ContractHttp("http://127.0.0.1:$port")
        val title = "Undo ${UUID.randomUUID()}"
        val baselineUrl = checkNotNull(System.getenv("WIKI_MW_BASE_URL")).trimEnd('/') +
            (System.getenv("WIKI_MW_SCRIPT_PATH") ?: "/w") + "/api.php"
        assertThat(observeUndo(local, "http://127.0.0.1:$port/w/api.php", title))
            .isEqualTo(observeUndo(baseline, baselineUrl, title))
    }

    @Test
    fun `compares historical undo and overlapping changes against MediaWiki 1_46_2`() {
        val local = ContractHttp("http://127.0.0.1:$port")
        val title = "Undo history ${UUID.randomUUID()}"
        assertThat(observeHistoricalUndo(local, title)).isEqualTo(observeHistoricalUndo(baseline(), title))
    }

    private fun observeHistoricalUndo(http: ContractHttp, title: String): Map<String, String> {
        fun currentSource(pageTitle: String) = http.actionRead("titles" to pageTitle).json
            .at("/query/pages/0/revisions/0/slots/main/content").asText()
        val initialSource = "one\ntwo\nthree\nfour\nfive"
        val targetSource = "ONE\ntwo\nthree\nfour\nfive"
        val latestSource = "ONE\ntwo\nthree\nfour\nFIVE"
        val first = http.actionCreate(title, initialSource).json.at("/edit/newrevid").asLong()
        assertThat(currentSource(title)).isEqualTo(initialSource)
        val target = http.actionEdit("title" to title, targetSource, first).json.at("/edit/newrevid").asLong()
        assertThat(currentSource(title)).isEqualTo(targetSource)
        val latest = http.actionEdit("title" to title, latestSource, target).json.at("/edit/newrevid").asLong()
        assertThat(currentSource(title)).isEqualTo(latestSource)
        assertThat(listOf(first, target, latest)).allMatch { it > 0 }
        val success = http.actionUndo("title" to title, target)
        assertThat(success.json.at("/edit/result").asText()).isEqualTo("Success")
        val restored = currentSource(title)
        assertThat(restored).isEqualTo("one\ntwo\nthree\nfour\nFIVE")
        val conflictTitle = "Overlap $title"
        val c1 = http.actionCreate(conflictTitle, "one\ntwo\nthree").json.at("/edit/newrevid").asLong()
        assertThat(currentSource(conflictTitle)).isEqualTo("one\ntwo\nthree")
        val c2 = http.actionEdit("title" to conflictTitle, "ONE\ntwo\nthree", c1).json.at("/edit/newrevid").asLong()
        assertThat(currentSource(conflictTitle)).isEqualTo("ONE\ntwo\nthree")
        val c3 = http.actionEdit("title" to conflictTitle, "OTHER\ntwo\nthree", c2).json.at("/edit/newrevid").asLong()
        assertThat(currentSource(conflictTitle)).isEqualTo("OTHER\ntwo\nthree")
        val conflict = http.actionUndo("title" to conflictTitle, c2)
        assertThat(http.actionRead("titles" to conflictTitle).json.at("/query/pages/0/revisions/0/revid").asLong()).isEqualTo(c3)
        return mapOf("result" to success.json.at("/edit/result").asText(), "source" to restored,
            "conflict" to conflict.json.at("/error/code").asText())
    }

    @Test
    fun `compares paragraph and adjacent historical undo against MediaWiki 1_46_2`() {
        val title = "Undo boundaries ${UUID.randomUUID()}"
        fun observe(http: ContractHttp): Map<String, String> {
            fun scenario(name: String, firstSource: String, targetSource: String, headSource: String): Pair<String, String> {
                val page = "$name $title"
                fun source() = http.actionRead("titles" to page).json.at("/query/pages/0/revisions/0/slots/main/content").asText()
                val first = http.actionCreate(page, firstSource).json.at("/edit/newrevid").asLong()
                assertThat(source()).isEqualTo(firstSource)
                val target = http.actionEdit("title" to page, targetSource, first).json.at("/edit/newrevid").asLong()
                assertThat(source()).isEqualTo(targetSource)
                val head = http.actionEdit("title" to page, headSource, target).json.at("/edit/newrevid").asLong()
                assertThat(source()).isEqualTo(headSource)
                assertThat(listOf(first, target, head)).allMatch { it > 0 }
                val undo = http.actionUndo("title" to page, target).json
                return (if (undo.has("edit")) undo.at("/edit/result").asText() else undo.at("/error/code").asText()) to source()
            }
            val paragraph = scenario("Paragraph", "Intro\n\nOld para\n\nEnd", "Intro\n\nNew para\n\nEnd", "Intro\n\nNew para\n\nEnd more")
            val adjacent = scenario("Adjacent", "old\nb", "new\nb", "new\nB")
            return mapOf("paragraphResult" to paragraph.first, "paragraphSource" to paragraph.second,
                "adjacentResult" to adjacent.first, "adjacentSource" to adjacent.second)
        }
        val expected = observe(baseline())
        assertThat(expected).containsEntry("paragraphResult", "Success").containsEntry("paragraphSource", "Intro\n\nOld para\n\nEnd more")
            .containsEntry("adjacentResult", "undofailure").containsEntry("adjacentSource", "new\nB")
        assertThat(observe(ContractHttp("http://127.0.0.1:$port"))).isEqualTo(expected)
    }

    @Test
    fun `documents conservative ambiguity gap for paragraph insertion and deletion`() {
        val short = "Intro\n\nEnd\nseparator\nlast"
        val long = "Intro\n\nNew\n\nEnd\nseparator\nlast"
        val suffix = UUID.randomUUID().toString()
        fun observe(http: ContractHttp, expectedSuccess: Boolean) {
            for ((label, sources) in listOf("Insert" to (short to long), "Delete" to (long to short))) {
                val (parent, targetSource) = sources
                val title = "Undo ambiguity $label $suffix"
                val headSource = "$targetSource changed"
                fun source() = http.actionRead("titles" to title).json.at("/query/pages/0/revisions/0/slots/main/content").asText()
                val first = http.actionCreate(title, parent).json.at("/edit/newrevid").asLong()
                assertThat(source()).isEqualTo(parent)
                val target = http.actionEdit("title" to title, targetSource, first).json.at("/edit/newrevid").asLong()
                assertThat(source()).isEqualTo(targetSource)
                val head = http.actionEdit("title" to title, headSource, target).json.at("/edit/newrevid").asLong()
                assertThat(source()).isEqualTo(headSource)
                assertThat(listOf(first, target, head)).allMatch { it > 0 }
                val undo = http.actionUndo("title" to title, target).json
                if (expectedSuccess) {
                    assertThat(undo.at("/edit/result").asText()).isEqualTo("Success")
                    assertThat(source()).isEqualTo("$parent changed")
                } else {
                    assertThat(undo.at("/error/code").asText()).isEqualTo("undofailure")
                    assertThat(source()).isEqualTo(headSource)
                    assertThat(http.actionRead("titles" to title).json.at("/query/pages/0/revisions/0/revid").asLong()).isEqualTo(head)
                }
            }
        }
        observe(baseline(), expectedSuccess = true)
        observe(ContractHttp("http://127.0.0.1:$port"), expectedSuccess = false)
    }

    private fun observeUndo(http: ContractHttp, apiUrl: String, title: String): Map<String, Any> {
        val first = http.actionCreate(title, "Original source").json.at("/edit/newrevid").asLong()
        val second = http.actionEdit("title" to title, "Changed source", first).json.at("/edit/newrevid").asLong()
        assertThat(first).isPositive()
        assertThat(second).isGreaterThan(first)
        fun requestUndo(target: Long, pageTitle: String = title): HttpResponse<String> {
            val params = mapOf("action" to "edit", "title" to pageTitle, "undo" to target.toString(),
                "text" to "This supplied text must not be saved", "summary" to "Undo latest edit",
                "token" to csrfToken, "format" to "json", "formatversion" to "2")
            val body = params.entries.joinToString("&") { (name, value) ->
                "$name=${URLEncoder.encode(value, Charsets.UTF_8)}"
            }
            val request = HttpRequest.newBuilder(URI.create(apiUrl)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString())
        }
        val response = requestUndo(second)
        assertThat(response.statusCode()).isEqualTo(200)
        val saved = com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("edit")
        assertThat(saved.path("result").asText()).isEqualTo("Success")
        assertThat(saved.path("oldrevid").asLong()).isEqualTo(second)
        val undo = saved.path("newrevid").asLong()
        assertThat(undo).isGreaterThan(second)
        val current = http.actionRead("titles" to title).json.at("/query/pages/0/revisions/0")
        assertThat(current.path("revid").asLong()).isEqualTo(undo)
        assertThat(current.path("parentid").asLong()).isEqualTo(second)
        assertThat(current.at("/slots/main/content").asText()).isEqualTo("Original source")
        val history = http.actionQuery("prop" to "revisions", "titles" to title, "rvlimit" to "3", "rvprop" to "ids").json
        assertThat(history.at("/query/pages/0/revisions").map { it.path("revid").asLong() })
            .containsExactly(undo, second, first)
        val missing = requestUndo(999_999_999L)
        assertThat(missing.statusCode()).isEqualTo(200)
        assertThat(missing.headers().firstValue("MediaWiki-API-Error").orElse("")).isEqualTo("nosuchrevid")
        val missingError = com.fasterxml.jackson.databind.ObjectMapper().readTree(missing.body()).path("error")
        assertThat(missingError.path("code").asText()).isEqualTo("nosuchrevid")
        assertThat(missingError.path("info").asText()).isEqualTo("There is no revision with ID 999999999.")
        assertThat(http.actionRead("titles" to title).json.at("/query/pages/0/revisions/0/revid").asLong()).isEqualTo(undo)
        val firstTitle = "First $title"
        val only = http.actionCreate(firstTitle, "Only revision").json.at("/edit/newrevid").asLong()
        val noParent = requestUndo(only, firstTitle)
        assertThat(noParent.statusCode()).isEqualTo(200)
        assertThat(noParent.headers().firstValue("MediaWiki-API-Error").orElse("")).isEqualTo("nosuchrevid")
        val noParentError = com.fasterxml.jackson.databind.ObjectMapper().readTree(noParent.body()).path("error")
        assertThat(noParentError.path("code").asText()).isEqualTo("nosuchrevid")
        assertThat(noParentError.path("info").asText()).isEqualTo("There is no revision with ID .")
        assertThat(http.actionRead("titles" to firstTitle).json.at("/query/pages/0/revisions/0/revid").asLong()).isEqualTo(only)
        val otherTitle = "Other $title"
        val otherFirst = http.actionCreate(otherTitle, "Other first").json.at("/edit/newrevid").asLong()
        val otherLatest = http.actionEdit("title" to otherTitle, "Other second", otherFirst).json.at("/edit/newrevid").asLong()
        val foreign = requestUndo(otherLatest)
        assertThat(foreign.statusCode()).isEqualTo(200)
        assertThat(foreign.headers().firstValue("MediaWiki-API-Error").orElse("")).isEqualTo("revwrongpage")
        val foreignError = com.fasterxml.jackson.databind.ObjectMapper().readTree(foreign.body()).path("error")
        assertThat(foreignError.path("code").asText()).isEqualTo("revwrongpage")
        assertThat(foreignError.path("info").asText()).isEqualTo("r$otherLatest is not a revision of $title.")
        assertThat(http.actionRead("titles" to title).json.at("/query/pages/0/revisions/0/revid").asLong()).isEqualTo(undo)
        return mapOf("result" to saved.path("result").asText(), "title" to saved.path("title").asText(),
            "contentmodel" to saved.path("contentmodel").asText(), "source" to current.at("/slots/main/content").asText())
    }

    @Test
    fun `compares stored Wikitext via Action and REST against MediaWiki 1_46_2 and Wikidiff2`() {
        val baseline = baseline()
        val local = ContractHttp("http://127.0.0.1:$port")
        val title = "Compare ${UUID.randomUUID()}"
        assertThat(observeComparison(local, title, allowCrossPageAction = false))
            .isEqualTo(observeComparison(baseline, title, allowCrossPageAction = true))
    }

    private fun observeComparison(http: ContractHttp, title: String, allowCrossPageAction: Boolean): Map<String, Any> {
        val from = http.actionCreate(title, "== 제목 ==\n가나다 old 끝\n").json.at("/edit/newrevid").asLong()
        val to = http.actionEdit("title" to title, "== 제목 ==\n가나다 new 끝\n", from).json.at("/edit/newrevid").asLong()
        val other = http.actionCreate("Other $title", "Other source").json.at("/edit/newrevid").asLong()
        assertThat(listOf(from, to, other)).doesNotHaveDuplicates().allMatch { it > 0 }

        val crossPage = http.actionCompare(from, other)
        assertThat(crossPage.status).isEqualTo(200)
        if (allowCrossPageAction) assertThat(crossPage.json.at("/compare/torevid").asLong()).isEqualTo(other)
        else assertThat(crossPage.json.at("/error/code").asText()).isEqualTo("unsupported")

        fun action(fromId: Long, toId: Long): String {
            val result = http.actionCompare(fromId, toId)
            assertThat(result.status).isEqualTo(200)
            val comparison = result.json["compare"]
            assertThat(comparison["fromrevid"].asLong()).isEqualTo(fromId)
            assertThat(comparison["torevid"].asLong()).isEqualTo(toId)
            assertThat(comparison["fromtitle"].asText()).isEqualTo(title)
            val body = comparison["body"].asText()
            if (fromId == toId) assertThat(body).isEmpty()
            else assertThat(body).contains("<del", "<ins", "== 제목 ==")
            return body
        }

        fun rest(fromId: Long, toId: Long): List<JsonNode> {
            val result = http.restCompare(fromId, toId)
            assertThat(result.status).isEqualTo(200)
            assertThat(result.json.at("/from/id").asLong()).isEqualTo(fromId)
            assertThat(result.json.at("/to/id").asLong()).isEqualTo(toId)
            assertThat(result.json.at("/from/slot_role").asText()).isEqualTo("main")
            assertThat(result.json.at("/to/slot_role").asText()).isEqualTo("main")
            return listOf(result.json.at("/from/sections"), result.json.at("/to/sections"), result.json["diff"])
        }

        val missing = 9_999_999L
        val actionMissing = http.actionCompare(from, missing)
        assertThat(actionMissing.status).isEqualTo(200)
        return mapOf(
            "actionForward" to action(from, to),
            "actionReverse" to action(to, from),
            "actionIdentical" to action(from, from),
            "actionMissing" to actionMissing.json["error"].select("code", "info"),
            "actionMissingHeader" to actionMissing.header("MediaWiki-API-Error"),
            "restForward" to rest(from, to),
            "restReverse" to rest(to, from),
            "restIdentical" to rest(from, from),
            "restMissingFrom" to restError(http.restCompare(missing, to)),
            "restMissingTo" to restError(http.restCompare(from, missing)),
            "restCrossPage" to restError(http.restCompare(from, other)),
        )
    }

    private fun observeHistory(http: ContractHttp, title: String): Map<String, Any> {
        val first = http.actionCreate(title, "First source").json.at("/edit/newrevid").asLong()
        val second = http.actionEdit("title" to title, "Second source", first).json.at("/edit/newrevid").asLong()
        val third = http.actionEdit("title" to title, "Third source", second).json.at("/edit/newrevid").asLong()
        assertThat(listOf(first, second, third)).doesNotHaveDuplicates().allMatch { it > 0 }
        fun history(vararg extra: Pair<String, String>) = http.actionQuery(
            "prop" to "revisions", "titles" to title, "rvprop" to "ids|timestamp|size", *extra,
        ).json
        val page = history("rvlimit" to "2")
        val revisions = page.at("/query/pages/0/revisions")
        assertThat(revisions.map { it["revid"].asLong() }).containsExactly(third, second)
        val cursor = page.at("/continue/rvcontinue").asText()
        assertThat(cursor).matches("[0-9]{14}\\|$first")
        val next = history("rvlimit" to "2", "rvcontinue" to cursor, "continue" to page.at("/continue/continue").asText())
        assertThat(next.at("/query/pages/0/revisions").map { it["revid"].asLong() }).containsExactly(first)
        assertThat(revisions.map { it["parentid"].asLong() }).containsExactly(second, first)
        val byId = http.actionQuery("prop" to "revisions", "revids" to first.toString(),
            "rvslots" to "main", "rvprop" to "ids|content")
        assertThat(byId.json.at("/query/pages/0/revisions/0/revid").asLong()).isEqualTo(first)
        assertThat(byId.json.at("/query/pages/0/revisions/0/slots/main/contentmodel").asText()).isEqualTo("wikitext")
        assertThat(history("continue" to "invalid").at("/error/code").asText()).isEqualTo("badcontinue")
        val pageId = page.at("/query/pages/0/pageid").asText()
        val byPageId = http.actionQuery("prop" to "revisions", "pageids" to pageId,
            "rvslots" to "main", "rvprop" to revisionFields)
        assertThat(byPageId.json.at("/query/pages/0/revisions/0/revid").asLong()).isEqualTo(third)
        val restOld = http.restRevision(first)
        assertThat(restOld.status).isEqualTo(200)
        assertThat(restOld.json["id"].asLong()).isEqualTo(first)
        val restHistory = http.restHistory(title)
        assertThat(restHistory.status).isEqualTo(200)
        assertThat(restHistory.json["revisions"].map { it["id"].asLong() }).containsExactly(third, second, first)
        val older = http.restHistory(title, query = "?older_than=$third")
        assertThat(older.json["revisions"].map { it["id"].asLong() }).containsExactly(second, first)
        assertThat(older.json["newer"].asText()).endsWith("?newer_than=$second")
        val newer = http.restHistory(title, query = "?newer_than=$first")
        assertThat(newer.json["revisions"].map { it["id"].asLong() }).containsExactly(third, second)
        assertThat(newer.json["older"].asText()).endsWith("?older_than=$second")
        val missingId = 999999999
        val missingAction = http.actionQuery("prop" to "revisions", "revids" to missingId.toString())
        assertThat(missingAction.json.at("/query/badrevids/$missingId/missing").asBoolean()).isTrue()
        val missingRest = http.restRevision(missingId.toLong())
        assertThat(missingRest.status).isEqualTo(404)
        assertThat(missingRest.json["errorKey"].asText()).isEqualTo("rest-nonexistent-revision")
        val unknownAnchor = http.restHistory(title, query = "?older_than=$missingId")
        assertThat(unknownAnchor.status).isEqualTo(404)
        assertThat(unknownAnchor.json["errorKey"].asText()).isEqualTo("rest-nonexistent-title-revision")
        val malformedAnchor = http.restHistory(title, query = "?older_than=invalid")
        assertThat(malformedAnchor.status).isEqualTo(400)
        assertThat(malformedAnchor.json["name"].asText()).isEqualTo("older_than")
        assertThat(malformedAnchor.json["failureCode"].asText()).isEqualTo("badinteger")
        val zeroAnchor = http.restHistory(title, query = "?older_than=0")
        assertThat(zeroAnchor.status).isEqualTo(400)
        assertThat(zeroAnchor.json["errorKey"].asText()).isEqualTo("rest-pagehistory-param-range-error")
        val canonical = http.restHistory(title.replaceFirstChar { it.lowercaseChar() }, query = "?older_than=$missingId")
        assertThat(canonical.status).isEqualTo(301)
        assertThat(canonical.header("Location")).endsWith("/$title/history?older_than=$missingId")
        return mapOf(
            "actionSizes" to revisions.map { it["size"].asInt() },
            "oldActionSource" to byId.json.at("/query/pages/0/revisions/0/slots/main/content").asText(),
            "oldRestSource" to restOld.json["source"].asText(),
            "restHistoryCount" to restHistory.json["revisions"].size(),
            "restContentModel" to restOld.json["content_model"].asText(),
            "restHistoryDeltas" to restHistory.json["revisions"].map { it["delta"] },
        )
    }

    private fun baseline(): ContractHttp {
        val baseline = ContractHttp(
            checkNotNull(System.getenv("WIKI_MW_BASE_URL")) { "Set WIKI_MW_BASE_URL to a disposable MediaWiki 1.46.2 server." }
                .trimEnd('/'),
            System.getenv("WIKI_MW_SCRIPT_PATH") ?: "/w",
        )
        val general = baseline.actionQuery("meta" to "siteinfo").json.at("/query/general")
        assertThat(general["generator"].asText()).isEqualTo("MediaWiki 1.46.2")
        assertThat(general["lang"].asText()).isEqualTo("en")
        assertThat(general["case"].asText()).isEqualTo("first-letter")
        return baseline
    }

    private fun observeEdit(http: ContractHttp, title: String, includeConflictDiff: Boolean): Map<String, Any> {
        val original = http.actionCreate(title, "Original").json.at("/edit/newrevid").asLong()
        assertThat(original).isPositive()
        val action = http.actionEdit("title" to title, "== Heading ==\nOld word end", original)
        val saved = action.json["edit"]
        assertThat(action.status).isEqualTo(200)
        assertThat(saved["result"].asText()).isEqualTo("Success")
        assertThat(saved["oldrevid"].asLong()).isEqualTo(original)
        val actionId = saved["newrevid"].asLong()
        assertThat(actionId).isNotEqualTo(original)

        val published = "== Heading ==\nNew word end"
        val rest = http.restEdit(title, published, actionId)
        assertThat(rest.status).isEqualTo(200)
        assertThat(rest.json["source"].asText()).isEqualTo(published)
        val revision = readAction(http, title).at("/query/pages/0/revisions/0")
        assertThat(revision["revid"]).isEqualTo(rest.json.at("/latest/id"))
        assertThat(revision["parentid"].asLong()).isEqualTo(actionId)
        assertThat(revision.at("/slots/main/content").asText()).isEqualTo(published)

        val draft = "== Heading ==\nDraft word end"
        val missingBase = 999_999_999L
        val staleAction = http.actionEdit("title" to title, draft, actionId)
        val unknownAction = http.actionEdit("title" to title, draft, missingBase)
        val staleRest = http.restEdit(title, draft, actionId)
        val unknownRest = http.restEdit(title, draft, missingBase)
        val currentId = revision["revid"].asLong()
        val storedComparison = http.restCompare(actionId, currentId)
        assertThat(storedComparison.status).isEqualTo(200)
        assertThat(storedComparison.json.at("/from/id").asLong()).isEqualTo(actionId)
        assertThat(storedComparison.json.at("/to/id").asLong()).isEqualTo(currentId)
        val diffTexts = storedComparison.json["diff"].map { it["text"].asText() }
        assertThat(diffTexts).containsExactly("== Heading ==", "OldNew word end")
        // The conflict diff follows the public REST contract. The baseline PUT is checked for its error fields.
        if (includeConflictDiff) {
            assertThat(staleRest.json.select("from", "to", "diff"))
                .isEqualTo(storedComparison.json.select("from", "to", "diff"))
        }
        assertThat(unknownRest.json.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("errorKey", "messageTranslations", "httpCode", "httpReason")
        val after = http.restRead(title)
        assertThat(after.json.at("/latest/id").asLong()).isEqualTo(currentId)
        assertThat(after.json["source"].asText()).isEqualTo(published)
        assertThat(http.restHistory(title).json["revisions"].map { it["id"].asLong() })
            .containsExactly(currentId, actionId, original)

        fun actionConflict(result: ContractHttp.Result): Map<String, JsonNode> {
            assertThat(result.status).isEqualTo(200)
            assertThat(result.header("MediaWiki-API-Error")).isEqualTo("editconflict")
            val error = result.json["error"]
            assertThat(error["code"].asText()).isEqualTo("editconflict")
            assertThat(error["info"].asText()).isEqualTo("Edit conflict: ${title.replace('_', ' ')}")
            assertThat(error["docref"].asText()).matches(
                "See https?://[^ ]+/api\\.php for API usage\\. Subscribe to the mediawiki-api-announce mailing list at " +
                    "&lt;https://lists\\.wikimedia\\.org/postorius/lists/mediawiki-api-announce\\.lists\\.wikimedia\\.org/&gt; " +
                    "for notice of API deprecations and breaking changes\\.",
            )
            return error.select("code", "info")
        }

        fun restConflict(result: ContractHttp.Result): Map<String, JsonNode> {
            assertThat(result.status).isEqualTo(409)
            assertThat(result.json["errorKey"].asText()).isEqualTo("editconflict")
            assertThat(result.json["httpCode"].asInt()).isEqualTo(409)
            assertThat(result.json["httpReason"].asText()).isEqualTo("Conflict")
            assertThat(result.json.at("/messageTranslations/en").asText()).isEqualTo("Edit conflict: ${title.replace('_', ' ')}")
            return result.json.select("errorKey", "messageTranslations", "httpCode", "httpReason")
        }

        val missing = "${title}_Missing"
        return mapOf(
            "action" to saved.select("result", "title", "contentmodel"),
            "rest" to rest.json.select("title", "key", "content_model", "source"),
            "actionConflict" to actionConflict(staleAction),
            "actionUnknownBase" to actionConflict(unknownAction),
            "restConflict" to restConflict(staleRest),
            "restUnknownBase" to restConflict(unknownRest),
            "publishedComparison" to listOf(
                storedComparison.json.at("/from/sections"),
                storedComparison.json.at("/to/sections"),
                storedComparison.json["diff"],
            ),
            "actionMissingTitle" to http.actionEdit("title" to missing, "Missing", actionId)
                .json.at("/error/code").asText(),
            "restMissingTitle" to restError(http.restEdit(missing, "Missing", actionId)),
        )
    }

    private fun observe(http: ContractHttp, actionTitle: String, restTitle: String, missingTitle: String): Map<String, Any> {
        val token = http.actionQuery("meta" to "tokens", "type" to "csrf").json.at("/query/tokens/csrftoken").asText()
        assertThat(token).isEqualTo(csrfToken)

        val edit = http.actionCreate(actionTitle, source, token)
        val saved = edit.json["edit"]
        assertThat(edit.status).isEqualTo(200)
        assertThat(saved["result"].asText()).isEqualTo("Success")
        assertThat(saved["new"].asBoolean()).isTrue()
        assertThat(saved["oldrevid"].asLong()).isZero()
        val actionPage = readAction(http, actionTitle)
        val revision = actionPage.at("/query/pages/0/revisions/0")
        assertThat(revision["revid"]).isEqualTo(saved["newrevid"])
        assertThat(revision["timestamp"]).isEqualTo(saved["newtimestamp"])
        Instant.parse(revision["timestamp"].asText())
        assertThat(actionPage.at("/query/pages/0/pageid")).isEqualTo(saved["pageid"])
        assertThat(revision["parentid"].asLong()).isZero()
        val slot = revision.at("/slots/main")
        assertThat(slot["content"].asText()).isEqualTo(source)
        val emptyTitle = "Empty $actionTitle"
        assertThat(http.actionCreate(emptyTitle, "", token).json.at("/edit/result").asText()).isEqualTo("Success")
        assertThat(readAction(http, emptyTitle).at("/query/pages/0/revisions/0/slots/main/content").textValue()).isEmpty()

        val restCreate = http.restCreate(restTitle, source, token)
        assertThat(restCreate.status).isEqualTo(201)
        val restKey = restTitle.replace(' ', '_')
        assertThat(URI.create(restCreate.header("Location")).path).endsWith("/rest.php/v1/page/$restKey")
        val restRead = http.restRead(restKey)
        assertThat(restRead.status).isEqualTo(200)
        assertThat(restRead.json).isEqualTo(restCreate.json)
        assertThat(restRead.json["source"].asText()).isEqualTo(source)
        val restRevision = readAction(http, restTitle).at("/query/pages/0/revisions/0")
        assertThat(restRead.json.at("/latest/id")).isEqualTo(restRevision["revid"])
        assertThat(restRead.json.at("/latest/timestamp")).isEqualTo(restRevision["timestamp"])
        val redirect = http.restRead(restKey.replaceFirstChar { it.lowercaseChar() })
        assertThat(redirect.status).isEqualTo(301)
        assertThat(URI.create(redirect.header("Location")).path).endsWith("/rest.php/v1/page/$restKey")

        val restConflict = http.restCreate(restTitle, source, token)
        val restMissing = http.restRead(missingTitle)
        val restInvalid = http.restRead("[Invalid]")
        val restBadToken = http.restCreate(missingTitle, source, token = null)
        val nullCommentTitle = "Summary $restTitle"
        val nullComment = http.restCreate(nullCommentTitle, source, token, comment = null)
        assertThat(nullComment.status).isEqualTo(400)
        assertThat(http.restRead(nullCommentTitle).status).isEqualTo(404)
        val projectedPage = restRead.json.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            remove(listOf("id", "latest", "license"))
        }
        return mapOf(
            "actionCreate" to saved.select("new", "result", "title", "contentmodel", "oldrevid"),
            "actionSlot" to slot,
            "actionNormalization" to actionPage.at("/query/normalized"),
            "actionMissing" to readAction(http, missingTitle).at("/query/pages/0"),
            "actionConflict" to http.actionCreate(actionTitle, source, token).json.at("/error/code").asText(),
            "actionInvalid" to http.actionCreate("[Invalid]", source, token).json.at("/error/code").asText(),
            "actionBadToken" to http.actionCreate(missingTitle, source, "wrong").json.at("/error/code").asText(),
            "restPage" to projectedPage,
            "restConflict" to restError(restConflict),
            "restMissing" to restError(restMissing),
            "restInvalid" to restError(restInvalid),
            "restBadToken" to restError(restBadToken),
            "restNullComment" to restError(nullComment),
        )
    }

    private fun readAction(http: ContractHttp, title: String) =
        http.actionRead("titles" to title.replace(' ', '_').replaceFirstChar { it.lowercaseChar() }).json

    private fun JsonNode.select(vararg names: String) = names.associateWith { this[it] }

    private fun restError(response: ContractHttp.Result) =
        response.status to response.json["errorKey"].asText()

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(properties: DynamicPropertyRegistry) = postgresProperties(properties)
    }
}
