// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.adapter.api.compat.action

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import wiki.liberty.adapter.api.InvalidCredentials
import wiki.liberty.adapter.api.PageHttp
import wiki.liberty.adapter.api.requestId
import wiki.liberty.application.CreatePage
import wiki.liberty.application.EditPage
import wiki.liberty.application.HistoryRequest
import wiki.liberty.application.PageActor
import wiki.liberty.application.PageApplicationService
import wiki.liberty.application.RevisionBase
import wiki.liberty.application.UndoPage
import wiki.liberty.domain.ComparisonRevisionMissing
import wiki.liberty.domain.EditConflict
import wiki.liberty.domain.ComparisonRevisionHidden
import wiki.liberty.domain.PageError
import wiki.liberty.domain.PageFailure
import wiki.liberty.domain.PageLookup
import wiki.liberty.domain.PageTitle
import wiki.liberty.domain.RevisionLookup
import wiki.liberty.domain.RevisionMetadata
import wiki.liberty.domain.RevisionSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.DateTimeException
import java.time.format.ResolverStyle
import java.util.Locale

class ActionFailure(val code: String, message: String) : RuntimeException(message)
data class ActionErrorEnvelope(val error: ActionError)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActionError(val code: String, val info: String, val docref: String? = null)

@RestController
class MediaWikiAction(private val pages: PageApplicationService, private val http: PageHttp) {
    @RequestMapping("/w/api.php", method = [RequestMethod.GET, RequestMethod.POST], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun action(request: HttpServletRequest): Map<String, Any> {
        val actor = http.actor(request)
        pages.requireRead(actor)
        val params = ActionParams(request.parameterMap)
        if (params.get("format") !in listOf(null, "json") || params.get("formatversion") !in listOf(null, "2")) {
            unsupported("Only format=json and formatversion=2 are supported.")
        }
        return when (params.get("action")) {
            "query" -> query(actor, params)
            "edit" -> edit(request, actor, params)
            else -> throw ActionFailure("badaction", "The requested action is unsupported.")
        }
    }

    @RequestMapping("/w/api.php", params = ["action=compare"], method = [RequestMethod.GET, RequestMethod.POST],
        produces = [MediaType.APPLICATION_JSON_VALUE])
    fun compareAction(request: HttpServletRequest): ResponseEntity<ByteArray> {
        val actor = http.actor(request)
        pages.requireRead(actor)
        val params = ActionParams(request.parameterMap)
        if (params.get("format") !in listOf(null, "json") || params.get("formatversion") !in listOf(null, "1", "2", "latest")) {
            unsupported("Only JSON formatversion 1 and 2 are supported for comparison.")
        }
        return http.comparisonResponse(compare(actor, params))
    }

    private fun compare(actor: PageActor, params: ActionParams): Map<String, Any> {
        params.only("fromrev", "torev", "prop", "difftype")
        if (params.get("difftype") !in listOf(null, "table")) unsupported("Only the table diff is supported.")
        val fields = (params.get("prop") ?: "diff|ids|title").split('|').filter { it.isNotEmpty() }.toSet()
        if (!setOf("diff", "ids", "title", "diffsize").containsAll(fields)) {
            unsupported("A compare property is unsupported.")
        }
        fun lookup(name: String): RevisionLookup.CompatId {
            val value = params.get(name) ?: throw ActionFailure("missingparam", "Specify $name.")
            return RevisionLookup.CompatId(value.toLongOrNull() ?: throw ActionFailure("badinteger", "Invalid $name."))
        }
        val comparison = pages.compareRevisions(actor, lookup("fromrev"), lookup("torev"))
        val versionTwo = params.get("formatversion") in listOf("2", "latest")
        return mapOf("compare" to buildMap {
            putAll(comparison.from.compareFields("from", fields))
            putAll(comparison.to.compareFields("to", fields))
            val hidden = if (versionTwo) true else ""
            if (comparison.from.revision.sourceRestricted) put("fromtexthidden", hidden)
            if (comparison.to.revision.sourceRestricted) put("totexthidden", hidden)
            if ("diff" in fields || "diffsize" in fields) {
                val html = actionDiff(comparison)
                if ("diff" in fields) put(if (versionTwo) "body" else "*", html)
                if ("diffsize" in fields) put("diffsize", html.toByteArray().size)
            }
        })
    }

    private fun RevisionSource.compareFields(prefix: String, fields: Set<String>): Map<String, Any> = buildMap {
        if ("ids" in fields) {
            put("${prefix}id", page.compatId)
            put("${prefix}revid", revision.compatId)
        }
        if ("title" in fields) {
            put("${prefix}ns", 0)
            put("${prefix}title", page.title.value)
        }
    }

    private fun edit(request: HttpServletRequest, actor: PageActor, params: ActionParams): Map<String, Any> {
        if (request.method != "POST") throw ActionFailure("mustbeposted", "The edit action requires POST.")
        if (request.contentType?.substringBefore(';') != MediaType.APPLICATION_FORM_URLENCODED_VALUE ||
            request.queryString != null
        ) unsupported("Send edit parameters in an application/x-www-form-urlencoded POST body.")
        val create = params.get("createonly") != null
        val undo = params.get("undo")
        if (undo != null) {
            params.only("title", "pageid", "undo", "text", "token", "summary", "nocreate", "baserevid")
        } else if (create) {
            params.only("title", "text", "token", "summary", "createonly")
        } else {
            params.only("title", "pageid", "text", "token", "summary", "baserevid", "nocreate")
        }
        if (params.required("token") != "+\\" || request.getHeader("Sec-Fetch-Site") == "cross-site") {
            throw ActionFailure("badtoken", "Invalid CSRF token.")
        }
        val summary = params.get("summary").orEmpty()
        val page = if (undo != null) {
            pages.undo(actor, request.requestId(), UndoPage(
                lookup(params.get("title"), params.get("pageid")),
                RevisionLookup.CompatId(positiveInteger(undo, "undo")), summary,
                params.get("baserevid")?.let { RevisionBase.Compat(positiveInteger(it, "baserevid")) },
            ))
        } else if (create) {
            pages.create(actor, request.requestId(), CreatePage(params.required("title"), 0, params.required("text"), summary))
        } else {
            val lookup = lookup(params.get("title"), params.get("pageid"))
            val base = RevisionBase.Compat(positiveInteger(params.required("baserevid"), "baserevid"))
            pages.edit(actor, request.requestId(), EditPage(lookup, base, params.required("text"), summary))
        }
        return mapOf("edit" to buildMap {
            if (create) put("new", true)
            putAll(mapOf(
                "result" to "Success", "pageid" to page.compatId, "title" to page.title.value,
                "contentmodel" to "wikitext", "oldrevid" to (page.compatParentRevisionId ?: 0L),
                "newrevid" to page.compatRevisionId, "newtimestamp" to page.createdAt,
            ))
        })
    }

    private fun query(actor: PageActor, params: ActionParams): Map<String, Any> {
        if (params.get("meta") == "tokens") {
            params.only("meta", "type")
            if (params.get("type") !in listOf(null, "csrf")) unsupported("Only CSRF tokens are supported.")
            return envelope(mapOf("tokens" to mapOf("csrftoken" to "+\\")))
        }
        params.only("prop", "titles", "pageids", "revids", "rvprop", "rvslots", "rvlimit", "rvcontinue", "continue")
        val props = params.required("prop").split('|').toSet()
        if (!setOf("info", "revisions").containsAll(props)) unsupported("Only info and revisions are supported.")
        val fields = (params.get("rvprop") ?: "ids|flags|timestamp|user|comment").split('|').toSet()
        val withContent = "content" in fields || "contentmodel" in fields
        if (!setOf("ids", "timestamp", "content", "contentmodel", "comment", "size", "flags", "user", "userid").containsAll(fields) ||
            params.get("rvslots") !in listOf(null, "main") || (withContent && params.get("rvslots") != "main")
        ) unsupported("An unsupported revision field or slot was requested.")

        val title = params.get("titles")
        val pageId = params.get("pageids")
        val limit = params.get("rvlimit")
        val continuation = params.get("rvcontinue")
        if (params.get("continue") !in listOf(null, "", "||")) throw PageFailure(PageError.INVALID_CURSOR)
        val revisionId = params.get("revids")
        if (revisionId != null) {
            if (title != null || pageId != null || limit != null || continuation != null || props != setOf("revisions")) {
                throw ActionFailure("invalidparammix", "Specify a single revision ID with prop=revisions.")
            }
            return byRevision(actor, positiveInteger(revisionId, "revids"), fields)
        }

        val lookup = lookup(title, pageId)
        val count = limit?.let { positiveInteger(it, "rvlimit").coerceAtMost(50).toInt() } ?: 1
        if ("content" in fields && count > 1) unsupported("Use revids or rvlimit=1 to read revision source.")
        val anchor = continuation?.let { parseContinuation(it) }
        val currentOnly = limit == null && continuation == null
        val history = try {
            pages.history(actor, HistoryRequest(
                lookup, count, anchor?.first, inclusive = true, anchorTime = anchor?.second,
                includeCurrent = currentOnly || "info" in props,
            ))
        } catch (failure: PageFailure) {
            when (failure.error) {
                PageError.INVALID_TITLE -> return envelope(mapOf("pages" to listOf(mapOf(
                    "title" to title.orEmpty(), "invalid" to true, "invalidreason" to "The title is invalid.",
                ))))
                PageError.PAGE_NOT_FOUND -> return missing(lookup, props)
                else -> throw failure
            }
        }
        val normalized = history.page.title.value
        val query = normalizedQuery(title, normalized)
        val entry = mutableMapOf<String, Any>("pageid" to history.page.compatId, "ns" to 0, "title" to normalized)
        if ("info" in props) history.current?.let { current ->
            entry.putAll(info())
            entry.putAll(mapOf("touched" to current.createdAt, "lastrevid" to current.compatId))
            current.size?.let { entry["length"] = it }
            if (current.parentId == null) entry["new"] = true
        }
        val selected = if (currentOnly) listOfNotNull(history.current) else history.revisions
        if ("revisions" in props && selected.isNotEmpty()) {
            entry["revisions"] = selected.map {
                val source = if ("content" in fields) pages.readRevision(actor, RevisionLookup.Id(it.id)).source else null
                revision(it, fields, source)
            }
        }
        query["pages"] = listOf(entry)
        val next = history.next
        if (next != null && "revisions" in props && !currentOnly) {
            return mapOf("query" to query, "continue" to mapOf(
                "rvcontinue" to "${actionTimestamp.format(next.createdAt)}|${next.compatId}", "continue" to "||",
            ))
        }
        return envelope(query)
    }

    private fun byRevision(actor: PageActor, id: Long, fields: Set<String>): Map<String, Any> {
        val result = try {
            pages.readRevision(actor, RevisionLookup.CompatId(id))
        } catch (failure: PageFailure) {
            if (failure.error != PageError.REVISION_NOT_FOUND) throw failure
            return envelope(mapOf("badrevids" to mapOf(id.toString() to mapOf("revid" to id, "missing" to true))))
        }
        return envelope(mapOf("pages" to listOf(mapOf(
            "pageid" to result.page.compatId, "ns" to 0, "title" to result.page.title.value,
            "revisions" to listOf(revision(result.revision, fields, result.source)),
        ))))
    }

    private fun parseContinuation(value: String): Pair<RevisionLookup.CompatId, Instant> {
        val parts = value.split('|', limit = 3)
        if (parts.size != 2) throw PageFailure(PageError.INVALID_CURSOR)
        val id = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: throw PageFailure(PageError.INVALID_CURSOR)
        val timestamp = try {
            Instant.from(actionTimestamp.parse(parts[0]))
        } catch (_: DateTimeException) {
            throw PageFailure(PageError.INVALID_CURSOR)
        }
        return RevisionLookup.CompatId(id) to timestamp
    }

    private fun normalizedQuery(title: String?, normalized: String): MutableMap<String, Any> =
        mutableMapOf<String, Any>().apply {
            if (title != null && normalized != title) {
                put("normalized", listOf(mapOf("fromencoded" to false, "from" to title, "to" to normalized)))
            }
        }

    private fun missing(lookup: PageLookup, props: Set<String>): Map<String, Any> {
        if (lookup is PageLookup.CompatId) {
            return envelope(mapOf("pages" to listOf(mapOf("pageid" to lookup.value, "missing" to true))))
        }
        val title = (lookup as PageLookup.Title).input
        val normalized = checkNotNull(PageTitle.parse(title)).value
        val entry = mutableMapOf<String, Any>("ns" to 0, "title" to normalized, "missing" to true)
        if ("info" in props) entry.putAll(info())
        return envelope(normalizedQuery(title, normalized).apply { put("pages", listOf(entry)) })
    }

    private fun revision(revision: RevisionMetadata, fields: Set<String>, source: String?): Map<String, Any> = buildMap {
        if ("ids" in fields) putAll(mapOf("revid" to revision.compatId, "parentid" to (revision.compatParentId ?: 0L)))
        if ("timestamp" in fields) put("timestamp", revision.createdAt)
        if ("flags" in fields) put("minor", false)
        if ("comment" in fields) put("comment", revision.summary)
        if ("size" in fields) revision.size?.let { put("size", it) }
        if ("user" in fields) {
            put("user", revision.actor)
            if (revision.actor == "anonymous") put("anon", true)
        }
        if ("userid" in fields) put("userid", if (revision.actor == "anonymous") 0 else 1)
        val slot = buildMap {
            if ("contentmodel" in fields || "content" in fields) put("contentmodel", "wikitext")
            if ("content" in fields) {
                put("contentformat", "text/x-wiki")
                put("content", checkNotNull(source))
            }
        }
        if (slot.isNotEmpty()) put("slots", mapOf("main" to slot))
    }

    private fun info() = mapOf("contentmodel" to "wikitext", "pagelanguage" to "en",
        "pagelanguagehtmlcode" to "en", "pagelanguagedir" to "ltr")

    private fun envelope(query: Map<String, Any>) = mapOf("batchcomplete" to true, "query" to query)

    private fun lookup(title: String?, id: String?): PageLookup {
        if ((title == null) == (id == null) || title?.contains('|') == true) {
            throw ActionFailure("invalidparammix", "Specify a single title or page ID.")
        }
        return if (title != null) PageLookup.Title(title) else PageLookup.CompatId(positiveInteger(checkNotNull(id), "pageid"))
    }

    private fun positiveInteger(value: String, name: String): Long =
        value.toLongOrNull()?.takeIf { it > 0 } ?: throw ActionFailure("badinteger", "Invalid $name.")

    private companion object {
        val actionTimestamp = DateTimeFormatter.ofPattern("uuuuMMddHHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT).withZone(ZoneOffset.UTC)
    }
}

private class ActionParams(private val parameters: Map<String, Array<String>>) {
    fun get(name: String) = parameters[name]?.singleOrNull()
    fun required(name: String) = get(name) ?: throw ActionFailure("missingparam", "The $name parameter must be set.")
    fun only(vararg names: String) {
        val allowed = names.toSet() + setOf("action", "format", "formatversion")
        if (parameters.any { (key, values) -> key !in allowed || values.size != 1 }) {
            unsupported("A parameter is unsupported or repeated.")
        }
    }
}

private fun unsupported(message: String): Nothing = throw ActionFailure("unsupported", message)

@RestControllerAdvice(assignableTypes = [MediaWikiAction::class])
class MediaWikiActionErrors {
    @ExceptionHandler(EditConflict::class)
    fun conflict(failure: EditConflict, request: HttpServletRequest, response: HttpServletResponse): ActionErrorEnvelope {
        response.setHeader("MediaWiki-API-Error", "editconflict")
        val docref = "See ${request.requestURL} for API usage. Subscribe to the mediawiki-api-announce mailing list at " +
            "&lt;https://lists.wikimedia.org/postorius/lists/mediawiki-api-announce.lists.wikimedia.org/&gt; " +
            "for notice of API deprecations and breaking changes."
        return ActionErrorEnvelope(ActionError("editconflict", "Edit conflict: ${failure.title.value}", docref))
    }

    @ExceptionHandler(ActionFailure::class)
    fun transport(failure: ActionFailure, request: HttpServletRequest, response: HttpServletResponse) =
        error(failure.code, failure.message.orEmpty()).also { setCompareErrorHeader(request, response, it) }

    @ExceptionHandler(PageFailure::class)
    fun domain(failure: PageFailure, request: HttpServletRequest, response: HttpServletResponse) = when (failure.error) {
        PageError.EXISTS -> error("articleexists", "The page you tried to create has been created already.")
        PageError.PAGE_NOT_FOUND -> if (request.getParameter("pageid") == null) {
            error("missingtitle", "The page you specified doesn't exist.")
        } else {
            error("nosuchpageid", "There is no page with the specified ID.")
        }
        PageError.EDIT_CONFLICT -> error("editconflict", "Edit conflict.")
        PageError.UNDO_CONFLICT, PageError.UNDO_TOO_LARGE -> error("undofailure", "The edit could not be undone due to conflicting intermediate edits.")
        PageError.UNDO_NO_PARENT -> error("nosuchrevid", "There is no revision with ID .")
        PageError.UNDO_WRONG_PAGE -> error("revwrongpage", request.getParameter("title")?.let {
            "r${request.getParameter("undo")} is not a revision of $it."
        } ?: "The revision does not belong to the specified page.")
        PageError.INVALID_CURSOR -> error("badcontinue", "Invalid revision continuation.")
        PageError.FORBIDDEN -> if (request.getParameter("action") == "edit") {
            error("permissiondenied", "The page operation is forbidden.")
        } else {
            error("readapidenied", "Read permission is required.")
        }
        PageError.INVALID_TITLE -> error("invalidtitle", "The title is invalid.")
        PageError.REVISION_NOT_FOUND -> {
            val revision = (failure as? ComparisonRevisionMissing)?.lookup as? RevisionLookup.CompatId
            val undo = request.getParameter("undo")
            if (undo != null) error("nosuchrevid", "There is no revision with ID $undo.")
            else if (revision == null) error("nosuchrevid", "The revision does not exist.")
            else error("nosuchrevid", "There is no revision with ID ${revision.value}.")
        }
        PageError.REVISION_SOURCE_HIDDEN -> {
            val revision = (failure as? ComparisonRevisionHidden)?.lookup as? RevisionLookup.CompatId
            if (revision == null) error("missingcontent", "The revision source is restricted.")
            else error("missingcontent", "Missing content for revision ID ${revision.value}.")
        }
        PageError.REVISION_PAGE_MISMATCH -> error("unsupported", "Cross-page comparison is outside this profile.")
        PageError.COMPARISON_TOO_LARGE -> error("comparison-too-large", "The revision comparison exceeds the processing or output limit.")
        else -> error("invalidparam", "The request is invalid.")
    }.also { setCompareErrorHeader(request, response, it) }

    @ExceptionHandler(InvalidCredentials::class)
    fun credentials() = error("permissiondenied", "Invalid Bearer credential.")

    private fun error(code: String, info: String) = ActionErrorEnvelope(ActionError(code, info))

    private fun setCompareErrorHeader(
        request: HttpServletRequest, response: HttpServletResponse, envelope: ActionErrorEnvelope,
    ) {
        if (request.getParameter("action") == "compare" || request.getParameter("undo") != null) {
            response.setHeader("MediaWiki-API-Error", envelope.error.code)
        }
    }
}
