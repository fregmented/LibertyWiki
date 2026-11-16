// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import wiki.liberty.domain.ComparisonRevisionHidden
import wiki.liberty.domain.ComparisonRevisionMissing
import wiki.liberty.domain.ComparisonSide
import wiki.liberty.domain.DiffResult
import wiki.liberty.domain.EditConflict
import wiki.liberty.domain.Page
import wiki.liberty.domain.PageError
import wiki.liberty.domain.PageFailure
import wiki.liberty.domain.PageLookup
import wiki.liberty.domain.PageTitle
import wiki.liberty.domain.RevisionComparison
import wiki.liberty.domain.RevisionLookup
import wiki.liberty.domain.RevisionMetadata
import wiki.liberty.domain.RevisionPage
import wiki.liberty.domain.RevisionSource
import wiki.liberty.domain.UndoResult
import wiki.liberty.domain.WikitextUndo
import wiki.liberty.domain.WikitextDiff
import wiki.liberty.domain.validUnicode
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

enum class PageActor { ANONYMOUS, OPERATOR }

@ConfigurationProperties("wiki.pages")
data class PagePolicy(
    val anonymousRead: Boolean = true,
    val anonymousEdit: Boolean = false,
    val anonymousCreate: Boolean = false,
)

data class CreatePage(val title: String, val namespaceId: Int, val source: String, val summary: String)
data class EditPage(val lookup: PageLookup, val base: RevisionBase, val source: String, val summary: String)
data class UndoPage(val lookup: PageLookup, val target: RevisionLookup, val summary: String, val base: RevisionBase? = null)
sealed interface RevisionBase {
    data class Native(val id: UUID) : RevisionBase
    data class Compat(val id: Long) : RevisionBase
}

data class PageRead(val page: Page?, val normalizedTitle: PageTitle? = null)
data class HistoryRequest(
    val page: PageLookup,
    val limit: Int,
    val anchor: RevisionLookup? = null,
    val direction: HistoryDirection = HistoryDirection.OLDER,
    val inclusive: Boolean = false,
    val anchorTime: Instant? = null,
    val includeCurrent: Boolean = false,
)

data class RevisionHistory(
    val page: RevisionPage,
    val revisions: List<RevisionMetadata>,
    val next: RevisionMetadata?,
    val hasNewer: Boolean,
    val hasOlder: Boolean,
    val current: RevisionMetadata?,
)

@Service
class PageApplicationService(
    private val repository: PageRepository,
    private val policy: PagePolicy,
    transactions: PlatformTransactionManager,
) {
    private val wikitextDiff = WikitextDiff()
    private val wikitextUndo = WikitextUndo()
    private val editTransaction = TransactionTemplate(transactions).apply { timeout = 8 }

    fun read(actor: PageActor, lookup: PageLookup): PageRead {
        requireRead(actor)
        return find(lookup).also { result ->
            if (actor == PageActor.ANONYMOUS && result.page?.sourceRestricted == true) {
                throw PageFailure(PageError.REVISION_SOURCE_HIDDEN)
            }
        }
    }

    fun readRevision(actor: PageActor, lookup: RevisionLookup): RevisionSource {
        requireRead(actor)
        val revision = repository.findRevision(lookup) ?: throw PageFailure(PageError.REVISION_NOT_FOUND)
        if (actor == PageActor.ANONYMOUS && revision.revision.sourceRestricted) {
            throw PageFailure(PageError.REVISION_SOURCE_HIDDEN)
        }
        return revision.copy(revision = visibleMetadata(actor, revision.revision))
    }

    fun compareRevisions(actor: PageActor, from: RevisionLookup, to: RevisionLookup): RevisionComparison {
        requireRead(actor)
        val (fromRevision, toRevision) = repository.findRevisions(from, to)
        val left = fromRevision ?: throw ComparisonRevisionMissing(ComparisonSide.FROM, from)
        if (actor == PageActor.ANONYMOUS && left.revision.sourceRestricted) {
            throw ComparisonRevisionHidden(ComparisonSide.FROM, from)
        }
        val right = toRevision ?: throw ComparisonRevisionMissing(ComparisonSide.TO, to)
        if (actor == PageActor.ANONYMOUS && right.revision.sourceRestricted) {
            throw ComparisonRevisionHidden(ComparisonSide.TO, to)
        }
        if (left.page.id != right.page.id) throw PageFailure(PageError.REVISION_PAGE_MISMATCH)
        return comparison(
            left.copy(revision = visibleMetadata(actor, left.revision)),
            right.copy(revision = visibleMetadata(actor, right.revision)),
        ) ?: throw PageFailure(PageError.COMPARISON_TOO_LARGE)
    }

    private fun visibleMetadata(actor: PageActor, revision: RevisionMetadata): RevisionMetadata {
        if (actor == PageActor.OPERATOR) return revision
        return revision.copy(
            size = if (revision.sourceRestricted) null else revision.size,
            delta = if (revision.sourceRestricted || revision.parentSourceRestricted) null else revision.delta,
        )
    }

    fun revisionPage(actor: PageActor, lookup: PageLookup): RevisionPage {
        requireRead(actor)
        val normalized = if (lookup is PageLookup.Title) PageLookup.Title(parseTitle(lookup.input).value) else lookup
        return repository.findRevisionPage(normalized) ?: throw PageFailure(PageError.PAGE_NOT_FOUND)
    }

    @Transactional(readOnly = true, timeout = 8)
    fun history(actor: PageActor, request: HistoryRequest): RevisionHistory {
        requireRead(actor)
        if (request.limit !in 1..50) throw PageFailure(PageError.INVALID_LIMIT)
        val page = revisionPage(actor, request.page)
        val anchor = request.anchor?.let {
            repository.findHistoryAnchor(page, it) ?: throw PageFailure(PageError.INVALID_CURSOR)
        }
        if (request.anchorTime != null && request.anchorTime != anchor?.createdAt) {
            throw PageFailure(PageError.INVALID_CURSOR)
        }
        val matches = repository.findHistory(
            page, HistoryWindow(anchor, request.direction, request.inclusive), request.limit + 1,
        )
        val next = matches.getOrNull(request.limit)
        val revisions = matches.take(request.limit)
        val older = request.direction == HistoryDirection.OLDER
        val current = if (request.includeCurrent) {
            revisions.find { it.id == page.currentRevisionId }
                ?: repository.findHistoryAnchor(page, RevisionLookup.Id(page.currentRevisionId))
        } else null
        return RevisionHistory(
            page, (if (older) revisions else revisions.asReversed()).map { visibleMetadata(actor, it) },
            next?.let { visibleMetadata(actor, it) },
            hasNewer = if (older) anchor != null && !request.inclusive else next != null,
            hasOlder = if (older) next != null else anchor != null,
            current = current?.let { visibleMetadata(actor, it) },
        )
    }

    private fun find(lookup: PageLookup): PageRead {
        return when (lookup) {
            is PageLookup.Title -> {
                val title = parseTitle(lookup.input)
                PageRead(repository.findByTitle(title), title)
            }
            is PageLookup.Id -> PageRead(repository.findById(lookup))
            is PageLookup.CompatId -> PageRead(repository.findByCompatId(lookup))
        }
    }

    @Transactional(timeout = 8)
    fun create(actor: PageActor, requestId: UUID, command: CreatePage): Page {
        requireRead(actor)
        if (actor == PageActor.ANONYMOUS && !(policy.anonymousEdit && policy.anonymousCreate)) {
            throw PageFailure(PageError.FORBIDDEN)
        }
        if (command.namespaceId != 0) throw PageFailure(PageError.INVALID_NAMESPACE)
        val title = parseTitle(command.title)
        val result = repository.create(
            FirstRevision(title, revision(actor, requestId, command.source, command.summary)),
        )
        return when (result) {
            is PageInsert.Created -> result.page
            PageInsert.DuplicateTitle -> throw PageFailure(PageError.EXISTS)
        }
    }

    fun edit(actor: PageActor, requestId: UUID, command: EditPage): Page {
        requireEdit(actor)
        val revision = revision(actor, requestId, command.source, command.summary)
        val result = checkNotNull(editTransaction.execute {
            val current = lockEditablePage(actor, command.lookup)
            val currentBase = when (val base = command.base) {
                is RevisionBase.Native -> base.id == current.revisionId
                is RevisionBase.Compat -> base.id == current.compatRevisionId
            }
            if (currentBase) EditResult.Committed(repository.append(current, revision))
            else captureConflict(command.base, current)
        })
        return when (result) {
            is EditResult.Committed -> result.page
            is EditResult.Conflict -> throw EditConflict(
                result.title, result.head?.revision?.id,
                result.base?.let { base -> result.head?.let { head -> comparison(base, head) } },
            )
        }
    }

    @Transactional(timeout = 8)
    fun undo(actor: PageActor, requestId: UUID, command: UndoPage): Page {
        requireEdit(actor)
        val observed = find(command.lookup).page ?: throw PageFailure(PageError.PAGE_NOT_FOUND)
        if (actor == PageActor.ANONYMOUS && observed.sourceRestricted) throw PageFailure(PageError.REVISION_SOURCE_HIDDEN)
        val current = lockEditablePage(actor, command.lookup)
        val baseMatches = when (val base = command.base) {
            is RevisionBase.Native -> base.id == current.revisionId
            is RevisionBase.Compat -> base.id == current.compatRevisionId
            null -> observed.revisionId == current.revisionId
        }
        if (!baseMatches) throw PageFailure(PageError.EDIT_CONFLICT)
        val target = repository.findRevision(command.target) ?: throw PageFailure(PageError.REVISION_NOT_FOUND)
        if (actor == PageActor.ANONYMOUS && target.revision.sourceRestricted) {
            val error = if (target.page.id == current.id) PageError.REVISION_SOURCE_HIDDEN else PageError.REVISION_NOT_FOUND
            throw PageFailure(error)
        }
        if (target.page.id != current.id) throw PageFailure(PageError.UNDO_WRONG_PAGE)
        // A historical undo would copy the restricted head into a new public revision.
        if (current.sourceRestricted && target.revision.id != current.revisionId) {
            throw PageFailure(PageError.REVISION_SOURCE_HIDDEN)
        }
        val parentId = target.revision.parentId ?: throw PageFailure(PageError.UNDO_NO_PARENT)
        val parent = repository.findRevision(RevisionLookup.Id(parentId))
            ?: throw PageFailure(PageError.REVISION_NOT_FOUND)
        // Undo creates a public revision, so it must never copy restricted source.
        if (parent.revision.sourceRestricted) throw PageFailure(PageError.REVISION_SOURCE_HIDDEN)
        val source = when (val result = wikitextUndo.apply(parent.source, target.source, current.source)) {
            is UndoResult.Applied -> result.source
            UndoResult.Conflict -> throw PageFailure(PageError.UNDO_CONFLICT)
            UndoResult.TooLarge -> throw PageFailure(PageError.UNDO_TOO_LARGE)
        }
        return repository.append(current, revision(actor, requestId, source, command.summary))
    }

    private fun requireEdit(actor: PageActor) {
        requireRead(actor)
        if (actor == PageActor.ANONYMOUS && !policy.anonymousEdit) throw PageFailure(PageError.FORBIDDEN)
    }

    private fun lockEditablePage(actor: PageActor, lookup: PageLookup): Page {
        val normalized = if (lookup is PageLookup.Title) PageLookup.Title(parseTitle(lookup.input).value) else lookup
        val current = repository.lockForEdit(normalized) ?: throw PageFailure(PageError.PAGE_NOT_FOUND)
        if (actor == PageActor.ANONYMOUS && current.sourceRestricted) {
            throw PageFailure(PageError.REVISION_SOURCE_HIDDEN)
        }
        return current
    }

    private fun captureConflict(base: RevisionBase, current: Page): EditResult.Conflict {
        val from = when (base) {
            is RevisionBase.Native -> RevisionLookup.Id(base.id)
            is RevisionBase.Compat -> RevisionLookup.CompatId(base.id)
        }
        val (left, right) = repository.findRevisions(from, RevisionLookup.Id(current.revisionId))
        return EditResult.Conflict(
            current.title,
            left?.takeIf { it.page.id == current.id && !it.revision.sourceRestricted },
            right?.takeUnless { it.revision.sourceRestricted },
        )
    }

    private fun comparison(left: RevisionSource, right: RevisionSource): RevisionComparison? =
        when (val diff = wikitextDiff.compare(left.source, right.source)) {
            is DiffResult.Success -> RevisionComparison(left, right, diff.lines)
            DiffResult.TooLarge -> null
        }

    private sealed interface EditResult {
        data class Committed(val page: Page) : EditResult
        data class Conflict(val title: PageTitle, val base: RevisionSource?, val head: RevisionSource?) : EditResult
    }

    private fun revision(actor: PageActor, requestId: UUID, source: String, summary: String): RevisionWrite {
        val bytes = source.toByteArray()
        if (bytes.size > 1_048_576 || !validUnicode(source)) throw PageFailure(PageError.INVALID_SOURCE)
        if (summary.codePointCount(0, summary.length) > 500 || !validUnicode(summary)) {
            throw PageFailure(PageError.INVALID_SUMMARY)
        }
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return RevisionWrite(source, sha256, summary, Instant.now().truncatedTo(ChronoUnit.SECONDS), actor, requestId)
    }

    fun requireRead(actor: PageActor) {
        if (actor == PageActor.ANONYMOUS && !policy.anonymousRead) throw PageFailure(PageError.FORBIDDEN)
    }

    private fun parseTitle(input: String): PageTitle =
        PageTitle.parse(input) ?: throw PageFailure(PageError.INVALID_TITLE)
}
