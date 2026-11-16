// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.adapter.api.nativeapi

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
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
import wiki.liberty.domain.EditConflict
import wiki.liberty.domain.Page
import wiki.liberty.domain.PageError
import wiki.liberty.domain.PageFailure
import wiki.liberty.domain.PageLookup
import wiki.liberty.domain.RevisionComparison
import wiki.liberty.domain.RevisionLookup
import wiki.liberty.domain.RevisionMetadata
import wiki.liberty.domain.RevisionSource
import java.net.URI
import java.time.Instant
import java.util.UUID

data class NativeCreatePage(val title: String, val namespaceId: Int, val source: String, val summary: String = "")
data class NativeEditPage(val baseRevisionId: String, val source: String, val summary: String = "")
data class NativeUndoPage(
    val revisionId: String,
    val summary: String = "",
    @param:JsonSetter(nulls = Nulls.FAIL) val baseRevisionId: String? = null,
)

data class NativeSourcePage(
    val id: UUID,
    val namespaceId: Int,
    val normalizedTitle: String,
    val currentRevisionId: UUID,
    val status: String,
    val source: String,
    val contentModel: String,
    val createdAt: Instant,
)

data class NativeError(val code: String, val message: String, val requestId: UUID)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NativeEditConflict(
    val code: String,
    val message: String,
    val requestId: UUID,
    val currentRevisionId: UUID?,
    val comparison: NativeRevisionComparison? = null,
)
data class NativeRevisionEntry(val id: UUID, val parentRevisionId: UUID?, val createdAt: Instant, val summary: String)
data class NativeHistory(val revisions: List<NativeRevisionEntry>, val nextCursor: UUID?)
data class NativeRevisionSource(
    val id: UUID,
    val parentRevisionId: UUID?,
    val createdAt: Instant,
    val summary: String,
    val pageId: UUID,
    val normalizedTitle: String,
    val source: String,
    val contentModel: String,
)
data class NativeComparisonRevision(val id: UUID, val pageId: UUID, val title: String, val timestamp: Instant)
data class NativeDiffFragment(val text: String, val changed: Boolean)
data class NativeDiffLine(
    val type: String,
    val fromLine: Int?,
    val toLine: Int?,
    val from: List<NativeDiffFragment>,
    val to: List<NativeDiffFragment>,
)
data class NativeRevisionComparison(
    val from: NativeComparisonRevision,
    val to: NativeComparisonRevision,
    val lines: List<NativeDiffLine>,
)

private fun RevisionComparison.native() = NativeRevisionComparison(
    from.nativeComparisonRevision(), to.nativeComparisonRevision(),
    lines.map { line ->
        NativeDiffLine(line.type.name.lowercase(), line.fromLine, line.toLine,
            line.from.map { NativeDiffFragment(it.text, it.changed) },
            line.to.map { NativeDiffFragment(it.text, it.changed) })
    },
)

private fun RevisionSource.nativeComparisonRevision() =
    NativeComparisonRevision(revision.id, page.id, page.title.value, revision.createdAt)

class NativePageMissing : RuntimeException()
enum class NativeIdKind { PAGE, BASE_REVISION, REVISION }
class InvalidNativeId(val kind: NativeIdKind) : RuntimeException()

@RestController
@RequestMapping("/api/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
class NativePages(private val pages: PageApplicationService, private val http: PageHttp) {
    @PostMapping("/pages")
    fun create(@RequestBody body: NativeCreatePage, request: HttpServletRequest): ResponseEntity<NativeSourcePage> {
        requireJson(request)
        val page = pages.create(
            http.actor(request), request.requestId(), CreatePage(body.title, body.namespaceId, body.source, body.summary),
        )
        return ResponseEntity.created(URI.create("/api/v1/pages/${page.id}")).body(page.native())
    }

    @PostMapping("/pages/{pageId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    fun edit(
        @PathVariable pageId: String, @RequestBody body: NativeEditPage, request: HttpServletRequest,
    ): NativeSourcePage {
        requireJson(request)
        val actor = http.actor(request)
        pages.requireRead(actor)
        val command = EditPage(
            PageLookup.Id(parseId(pageId)), RevisionBase.Native(parseId(body.baseRevisionId, NativeIdKind.BASE_REVISION)),
            body.source, body.summary,
        )
        return pages.edit(actor, request.requestId(), command).native()
    }

    @PostMapping("/pages/{pageId}/undos")
    @ResponseStatus(HttpStatus.CREATED)
    fun undo(
        @PathVariable pageId: String, @RequestBody body: NativeUndoPage, request: HttpServletRequest,
    ): NativeSourcePage {
        requireJson(request)
        val actor = http.actor(request)
        pages.requireRead(actor)
        val command = UndoPage(
            PageLookup.Id(parseId(pageId)), RevisionLookup.Id(parseId(body.revisionId, NativeIdKind.REVISION)),
            body.summary, body.baseRevisionId?.let { RevisionBase.Native(parseId(it, NativeIdKind.BASE_REVISION)) },
        )
        return pages.undo(actor, request.requestId(), command).native()
    }

    @GetMapping("/pages/{pageId}/revisions")
    fun history(
        @PathVariable pageId: String,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) cursor: String?,
        request: HttpServletRequest,
    ): NativeHistory {
        val actor = http.actor(request)
        pages.requireRead(actor)
        val page = PageLookup.Id(parseId(pageId))
        val count = limit?.let { it.toIntOrNull() ?: throw PageFailure(PageError.INVALID_LIMIT) } ?: 20
        val anchor = cursor?.let {
            try {
                RevisionLookup.Id(parseId(it, NativeIdKind.REVISION))
            } catch (_: InvalidNativeId) {
                throw PageFailure(PageError.INVALID_CURSOR)
            }
        }
        val history = pages.history(actor, HistoryRequest(page, count, anchor))
        return NativeHistory(
            history.revisions.map { it.native() },
            history.revisions.lastOrNull()?.id?.takeIf { history.hasOlder },
        )
    }

    @GetMapping("/revisions/{revisionId}")
    fun revision(@PathVariable revisionId: String, request: HttpServletRequest): NativeRevisionSource {
        val actor = http.actor(request)
        pages.requireRead(actor)
        val source = pages.readRevision(actor, RevisionLookup.Id(parseId(revisionId, NativeIdKind.REVISION)))
        val revision = source.revision
        return NativeRevisionSource(
            revision.id, revision.parentId, revision.createdAt, revision.summary,
            source.page.id, source.page.title.value, source.source, "wikitext",
        )
    }

    @GetMapping("/revisions/{fromId}/compare/{toId}")
    fun compare(
        @PathVariable fromId: String, @PathVariable toId: String, request: HttpServletRequest,
    ): ResponseEntity<ByteArray> {
        val actor = http.actor(request)
        pages.requireRead(actor)
        val from = RevisionLookup.Id(parseId(fromId, NativeIdKind.REVISION))
        val to = RevisionLookup.Id(parseId(toId, NativeIdKind.REVISION))
        return http.comparisonResponse(pages.compareRevisions(actor, from, to).native())
    }

    @GetMapping("/pages/by-title/{title}")
    fun byTitle(@PathVariable title: String, request: HttpServletRequest): NativeSourcePage {
        val actor = http.actor(request)
        pages.requireRead(actor)
        if (';' in request.requestURI) throw PageFailure(PageError.INVALID_TITLE)
        return read(PageLookup.Title(title), actor)
    }

    @GetMapping("/pages/{pageId}")
    fun byId(@PathVariable pageId: String, request: HttpServletRequest): NativeSourcePage {
        val actor = http.actor(request)
        pages.requireRead(actor)
        return read(PageLookup.Id(parseId(pageId)), actor)
    }

    private fun parseId(value: String, kind: NativeIdKind = NativeIdKind.PAGE): UUID =
        try {
            UUID.fromString(value).also {
                if (!it.toString().equals(value, ignoreCase = true)) throw InvalidNativeId(kind)
            }
        } catch (_: IllegalArgumentException) {
            throw InvalidNativeId(kind)
        }

    private fun requireJson(request: HttpServletRequest) {
        if (request.contentType?.substringBefore(';')?.equals(MediaType.APPLICATION_JSON_VALUE, ignoreCase = true) != true) {
            throw HttpMediaTypeNotSupportedException("Expected application/json.")
        }
    }

    private fun read(lookup: PageLookup, actor: PageActor): NativeSourcePage =
        pages.read(actor, lookup).page?.native() ?: throw NativePageMissing()

    private fun Page.native() = NativeSourcePage(id, 0, title.value, revisionId, "ACTIVE", source, "wikitext", createdAt)
    private fun RevisionMetadata.native() = NativeRevisionEntry(id, parentId, createdAt, summary)
}

@RestControllerAdvice(assignableTypes = [NativePages::class])
class NativePageErrors(private val http: PageHttp) {
    @ExceptionHandler(EditConflict::class)
    fun conflict(failure: EditConflict, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val error = NativeEditConflict(
            PageError.EDIT_CONFLICT.name, "The page has changed since the base revision.",
            request.requestId(), failure.currentRevisionId,
        )
        return http.comparisonResponse(
            error.copy(comparison = failure.comparison?.native()), HttpStatus.CONFLICT, error,
        )
    }

    @ExceptionHandler(PageFailure::class)
    fun domain(failure: PageFailure, request: HttpServletRequest): ResponseEntity<NativeError> {
        val status = when (failure.error) {
            PageError.FORBIDDEN, PageError.REVISION_SOURCE_HIDDEN -> HttpStatus.FORBIDDEN
            PageError.EXISTS, PageError.EDIT_CONFLICT, PageError.UNDO_CONFLICT, PageError.UNDO_NO_PARENT -> HttpStatus.CONFLICT
            PageError.PAGE_NOT_FOUND, PageError.REVISION_NOT_FOUND, PageError.UNDO_WRONG_PAGE -> HttpStatus.NOT_FOUND
            PageError.INVALID_CURSOR, PageError.INVALID_LIMIT, PageError.REVISION_PAGE_MISMATCH -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.UNPROCESSABLE_ENTITY
        }
        val message = when (failure.error) {
            PageError.FORBIDDEN -> "The page operation is forbidden."
            PageError.EXISTS -> "A page with this title already exists."
            PageError.EDIT_CONFLICT, PageError.UNDO_CONFLICT -> "The page has changed since the base revision."
            PageError.UNDO_NO_PARENT -> "The first revision cannot be undone."
            PageError.UNDO_TOO_LARGE -> "The undo exceeds the processing limit."
            PageError.PAGE_NOT_FOUND -> "Page not found."
            PageError.REVISION_NOT_FOUND, PageError.UNDO_WRONG_PAGE -> "Revision not found."
            PageError.REVISION_SOURCE_HIDDEN -> "The revision source is restricted."
            PageError.REVISION_PAGE_MISMATCH -> "Both revisions must belong to the same page."
            PageError.COMPARISON_TOO_LARGE -> "The revision comparison exceeds the processing or output limit."
            PageError.INVALID_CURSOR -> "Expected a revision UUID belonging to this page."
            PageError.INVALID_LIMIT -> "Limit must be an integer between 1 and 50."
            PageError.INVALID_TITLE -> "The title is invalid or outside the supported namespace."
            PageError.INVALID_NAMESPACE -> "Only the main namespace is supported."
            PageError.INVALID_SOURCE -> "Source must be valid Unicode and at most 1 MiB in UTF-8."
            PageError.INVALID_SUMMARY -> "Summary must be valid Unicode and at most 500 characters."
        }
        val code = when (failure.error) {
            PageError.UNDO_WRONG_PAGE -> PageError.REVISION_NOT_FOUND
            PageError.UNDO_CONFLICT -> PageError.EDIT_CONFLICT
            else -> failure.error
        }
        return error(status, code.name, message, request)
    }

    @ExceptionHandler(NativePageMissing::class)
    fun missing(request: HttpServletRequest) = error(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "Page not found.", request)

    @ExceptionHandler(InvalidNativeId::class)
    fun invalidId(failure: InvalidNativeId, request: HttpServletRequest) = when (failure.kind) {
        NativeIdKind.BASE_REVISION ->
            error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BASE_REVISION_ID", "Expected a base revision UUID.", request)
        NativeIdKind.PAGE ->
            error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAGE_ID", "Expected a page UUID.", request)
        NativeIdKind.REVISION ->
            error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REVISION_ID", "Expected a revision UUID.", request)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidBody(request: HttpServletRequest) =
        error(HttpStatus.BAD_REQUEST, "INVALID_BODY", "Expected a valid JSON request body.", request)

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun invalidMediaType(request: HttpServletRequest) =
        error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_MEDIA_TYPE", "Expected application/json.", request)

    @ExceptionHandler(InvalidCredentials::class)
    fun credentials(request: HttpServletRequest): ResponseEntity<NativeError> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", "Bearer")
            .body(NativeError("INVALID_CREDENTIALS", "Invalid Bearer credential.", request.requestId()))

    private fun error(status: HttpStatus, code: String, message: String, request: HttpServletRequest) =
        ResponseEntity.status(status).body(NativeError(code, message, request.requestId()))
}
