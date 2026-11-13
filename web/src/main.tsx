// SPDX-License-Identifier: AGPL-3.0-only
import { useState } from 'react'
import type { FormEvent } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

type SourcePage = {
  id: string
  currentRevisionId: string
  normalizedTitle: string
  source: string
}

type RevisionSummary = {
  id: string
  parentRevisionId: string | null
  createdAt: string
  summary: string
}
type RevisionHistory = { revisions: RevisionSummary[]; nextCursor: string | null }
type Revision = RevisionSummary & { pageId: string; normalizedTitle: string; source: string; contentModel: string }
type CompareFragment = { text: string; changed: boolean }
type CompareLine = { type: 'context' | 'added' | 'deleted' | 'changed'; fromLine: number | null; toLine: number | null; from: CompareFragment[]; to: CompareFragment[] }
type Comparison = { from: CompareRevision; to: CompareRevision; lines: CompareLine[] }
type CompareRevision = { id: string; pageId: string; title: string; timestamp: string }
type PendingDraft = { pageId: string; baseRevisionId: string; source: string; summary: string }

const errorMessages: Record<string, string> = {
  EDIT_CONFLICT: '다른 편집이 먼저 저장되었습니다. 작성한 원문을 보존했습니다.',
  FORBIDDEN: '이 작업을 수행할 권한이 없습니다.',
  INVALID_CREDENTIALS: '운영자 토큰을 확인해 주세요.',
  INVALID_TITLE: '일반 이름공간에서 사용할 수 있는 제목을 입력해 주세요.',
  INVALID_SOURCE: '원문은 올바른 유니코드 문자열이어야 하며 UTF-8 기준 1 MiB 이하여야 합니다.',
  INVALID_SUMMARY: '편집 요약은 500자까지 입력할 수 있습니다.',
  EXISTS: '이 제목의 문서가 이미 있습니다.',
  COMPARISON_TOO_LARGE: '비교할 리비전이 허용된 크기를 초과했습니다.',
  REVISION_NOT_FOUND: '리비전을 찾을 수 없습니다.',
  UNDO_NO_PARENT: '이전 리비전이 없어 되돌릴 수 없습니다.',
  UNDO_TOO_LARGE: '자동 되돌리기의 처리 한도를 초과했습니다. 원문을 직접 편집해 주세요. 작성 중인 내용은 유지됩니다.',
  REVISION_SOURCE_HIDDEN: '이 리비전의 원문을 볼 권한이 없습니다.',
  PAGE_NOT_FOUND: '이 제목의 문서를 찾을 수 없습니다.',
}

class RequestError extends Error {
  constructor(public code: string, public currentRevisionId?: string, public comparison?: Comparison) {
    super(errorMessages[code] ?? '요청을 처리할 수 없습니다.')
  }
}

async function request<T>(path: string, token: string, body?: object): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (body) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, {
    method: body ? 'POST' : 'GET',
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15_000),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    throw new RequestError(error.code, error.currentRevisionId, error.comparison)
  }
  return response.json()
}

function ComparisonView({ comparison, title }: { comparison: Comparison; title: string }) {
  return (
    <section aria-label={title}>
      <h3>{title}</h3>
      <p>시작: {comparison.from.id} · <time dateTime={comparison.from.timestamp}>{comparison.from.timestamp}</time><br />
        끝: {comparison.to.id} · <time dateTime={comparison.to.timestamp}>{comparison.to.timestamp}</time></p>
      {comparison.lines.length === 0 ? <p>차이가 없습니다.</p> : (
        <div className="comparison" aria-label="Wikitext 차이">
          {comparison.lines.map((line, index) => (
            <div className={`diff-line diff-${line.type}`} key={index}>
              <span className="diff-kind">{{ context: '변경 없음', added: '추가', deleted: '삭제', changed: '변경' }[line.type]}</span>
              {([['시작', line.fromLine, line.from], ['끝', line.toLine, line.to]] as const).map(([side, number, fragments]) => number != null && (
                <div key={side} className="diff-side"><span>{side} {number}: </span><code>{fragments.map((fragment, fragmentIndex) => fragment.changed ? <mark key={fragmentIndex}>{fragment.text}</mark> : <span key={fragmentIndex}>{fragment.text}</span>)}</code></div>
              ))}
            </div>
          ))}
        </div>
      )}
    </section>
  )
}

const requestPage = (path: string, token: string, body?: object) => request<SourcePage>(path, token, body)

function App() {
  const [token, setToken] = useState('')
  const [page, setPage] = useState<SourcePage>()
  const [error, setError] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  const [draft, setDraft] = useState('')
  const [summary, setSummary] = useState('')
  const [baseRevisionId, setBaseRevisionId] = useState('')
  const [conflict, setConflict] = useState<RequestError>()
  const [history, setHistory] = useState<RevisionSummary[]>()
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [revision, setRevision] = useState<Revision>()
  const [compareFrom, setCompareFrom] = useState('')
  const [compareTo, setCompareTo] = useState('')
  const [comparison, setComparison] = useState<Comparison>()
  const [pendingDraft, setPendingDraft] = useState<PendingDraft>()
  const conflictRevisionId = conflict?.currentRevisionId

  function clearHistory() {
    setHistory(undefined)
    setNextCursor(null)
    setRevision(undefined)
    setCompareFrom('')
    setCompareTo('')
    setComparison(undefined)
  }

  function changeToken(value: string) {
    if (page && (draft !== page.source || summary || conflict || baseRevisionId !== page.currentRevisionId)) {
      setPendingDraft({ pageId: page.id, baseRevisionId, source: draft, summary })
    }
    setToken(value)
    setPage(undefined)
    setDraft('')
    setSummary('')
    setConflict(undefined)
    clearHistory()
    setError('')
    setStatus('')
  }

  async function submit(event: FormEvent<HTMLFormElement>, create: boolean) {
    event.preventDefault()
    if (busy || conflict) return
    const fields = new FormData(event.currentTarget)
    const title = String(fields.get('title'))
    const savedDraft = pendingDraft ?? (!create && page && (draft !== page.source || summary || baseRevisionId !== page.currentRevisionId)
      ? { pageId: page.id, baseRevisionId, source: draft, summary } : undefined)
    setError('')
    setStatus('요청 처리 중')
    setPage(undefined)
    clearHistory()
    setBusy(true)
    try {
      if (!create && (title === '.' || title === '..')) throw new Error(errorMessages.INVALID_TITLE)
      const result = create
        ? await requestPage('/api/v1/pages', token, {
          title,
          namespaceId: 0,
          source: String(fields.get('source')),
          summary: String(fields.get('summary')),
        })
        : await requestPage(`/api/v1/pages/by-title/${encodeURIComponent(title)}`, token)
      const restored = savedDraft?.pageId === result.id ? savedDraft : undefined
      setPendingDraft(undefined)
      setPage(result)
      setBaseRevisionId(restored?.baseRevisionId ?? result.currentRevisionId)
      setDraft(restored?.source ?? result.source)
      setSummary(restored?.summary ?? '')
      if (restored && restored.baseRevisionId !== result.currentRevisionId) {
        setConflict(new RequestError('EDIT_CONFLICT', result.currentRevisionId))
        setError(errorMessages.EDIT_CONFLICT)
      }
      setStatus(restored ? '작성 중인 원문과 요약을 복원했습니다.' : create ? '문서를 만들었습니다.' : '현재 원문을 불러왔습니다.')
    } catch (failure) {
      if (failure instanceof DOMException && failure.name === 'TimeoutError') {
        setError('응답 시간이 초과되었습니다.')
      } else {
        setError(failure instanceof Error ? failure.message : '요청을 처리할 수 없습니다.')
      }
      setStatus('')
    } finally {
      setBusy(false)
    }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!page || busy || conflict) return
    setBusy(true)
    setError('')
    setStatus('저장 중')
    try {
      const result = await requestPage(`/api/v1/pages/${encodeURIComponent(page.id)}/revisions`, token, {
        baseRevisionId,
        source: draft,
        summary,
      })
      setPage(result)
      setBaseRevisionId(result.currentRevisionId)
      clearHistory()
      setDraft(result.source)
      setSummary('')
      setStatus('편집을 저장했습니다.')
    } catch (failure) {
      if (failure instanceof RequestError && failure.code === 'EDIT_CONFLICT') {
        setConflict(failure)
      }
      setError(failure instanceof Error ? failure.message : '요청을 처리할 수 없습니다.')
      setStatus('')
    } finally {
      setBusy(false)
    }
  }

  async function fetchHistory(cursor?: string) {
    if (!page || busy) return
    setBusy(true)
    setError('')
    try {
      const params = new URLSearchParams({ limit: '20' })
      if (cursor) params.set('cursor', cursor)
      const result = await request<RevisionHistory>(`/api/v1/pages/${encodeURIComponent(page.id)}/revisions?${params}`, token)
      setHistory(previous => cursor ? [...(previous ?? []), ...result.revisions] : result.revisions)
      setNextCursor(result.nextCursor)
      if (!cursor) {
        setCompareFrom('')
        setCompareTo('')
        setComparison(undefined)
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '요청을 처리할 수 없습니다.')
    } finally {
      setBusy(false)
    }
  }

  async function undoRevision(id: string) {
    if (!page || busy || conflict) return
    setBusy(true)
    setError('')
    setStatus('편집을 되돌리는 중')
    try {
      const result = await requestPage(`/api/v1/pages/${encodeURIComponent(page.id)}/undos`, token, { revisionId: id, baseRevisionId: page.currentRevisionId })
      const hasDraft = draft !== page.source || summary !== ''
      setPage(result)
      if (hasDraft) {
        setConflict(new RequestError('EDIT_CONFLICT', result.currentRevisionId))
      } else {
        setBaseRevisionId(result.currentRevisionId)
        setDraft(result.source)
      }
      clearHistory()
      setStatus(hasDraft ? '편집을 되돌렸습니다. 작성 중인 원문과 요약은 유지됩니다.' : '편집을 되돌렸습니다.')
      try {
        const refreshed = await request<RevisionHistory>(`/api/v1/pages/${encodeURIComponent(page.id)}/revisions?limit=20`, token)
        setHistory(refreshed.revisions)
        setNextCursor(refreshed.nextCursor)
      } catch {
        setError('되돌리기는 저장되었지만 이력을 새로 불러오지 못했습니다. 문서 이력을 다시 조회하세요.')
      }
    } catch (failure) {
      if (failure instanceof RequestError && failure.code === 'EDIT_CONFLICT') {
        setError('후속 편집과 겹치거나 적용 위치가 불명확하거나 최신 판이 바뀌어 되돌릴 수 없습니다. 문서를 다시 조회하고 최신 이력을 확인하세요.')
        clearHistory()
      } else {
        setError(failure instanceof Error ? failure.message : '요청을 처리할 수 없습니다.')
      }
      setStatus('')
    } finally {
      setBusy(false)
    }
  }

  async function fetchRevision(id: string) {
    if (!page || busy) return
    setBusy(true)
    setError('')
    setRevision(undefined)
    try {
      const result = await request<Revision>(`/api/v1/revisions/${encodeURIComponent(id)}`, token)
      if (result.pageId !== page.id) throw new Error('이 문서의 리비전을 불러오지 못했습니다.')
      setRevision(result)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '요청을 처리할 수 없습니다.')
    } finally {
      setBusy(false)
    }
  }

  async function compareRevisions() {
    if (!page || busy || !compareFrom || !compareTo) return
    setBusy(true)
    setError('')
    setStatus('리비전 비교 중')
    setComparison(undefined)
    try {
      const result = await request<Comparison>(`/api/v1/revisions/${encodeURIComponent(compareFrom)}/compare/${encodeURIComponent(compareTo)}`, token)
      if (result.from.pageId !== page.id || result.to.pageId !== page.id) throw new Error('이 문서의 리비전을 비교하지 못했습니다.')
      setComparison(result)
      setStatus('리비전 비교를 불러왔습니다.')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '요청을 처리할 수 없습니다.')
      setStatus('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <header>
        <div className="brand">LibertyWiki</div>
      </header>
      <main>
        <h1>문서 원문</h1>
        <label className="credential">
          운영자 토큰 (권한이 필요한 경우)
          <input disabled={busy} type="password" autoComplete="off" value={token} onChange={event => changeToken(event.target.value)} />
        </label>
        <div className="forms">
          <form onSubmit={event => void submit(event, false)}>
            <h2>제목으로 찾기</h2>
            <label>조회할 제목<input name="title" required /></label>
            <button disabled={busy || !!conflict}>원문 조회</button>
          </form>
          <form onSubmit={event => void submit(event, true)}>
            <h2>새 문서 만들기</h2>
            <label>새 문서 제목<input name="title" required /></label>
            <label>Wikitext 원문<textarea name="source" rows={8} spellCheck={false} /></label>
            <label>편집 요약 (선택)<input name="summary" maxLength={500} /></label>
            <button disabled={busy || !!conflict}>문서 생성</button>
          </form>
        </div>
        <div role="status">{status}</div>
        <div role="alert">{error}</div>
        {page && (
          <section aria-labelledby="result-title">
            <h2 id="result-title">{page.normalizedTitle}</h2>
            <pre aria-label={conflict && conflictRevisionId !== page.currentRevisionId ? '조회한 Wikitext 원문' : '현재 Wikitext 원문'}>{page.source}</pre>
            <div>
              <h3>문서 이력</h3>
              <button type="button" disabled={busy} onClick={() => void fetchHistory()}>문서 이력 보기</button>
              {history && (
                <>
                  <p>비교할 시작과 끝 리비전을 각각 선택하세요. 선택 순서대로 비교합니다.</p>
                  <ul>
                    {history.map(item => (
                      <li key={item.id}>
                        <label className="revision-choice"><input type="radio" name="compare-from" checked={compareFrom === item.id} disabled={busy} onChange={() => { setCompareFrom(item.id); setComparison(undefined) }} /> 시작 {item.id}</label>{' '}
                        <label className="revision-choice"><input type="radio" name="compare-to" checked={compareTo === item.id} disabled={busy} onChange={() => { setCompareTo(item.id); setComparison(undefined) }} /> 끝 {item.id}</label>{' '}
                        <time dateTime={item.createdAt}>{item.createdAt}</time>{' · '}
                        <span>{item.summary}</span>{' '}
                        <button type="button" disabled={busy} onClick={() => void fetchRevision(item.id)}>리비전 {item.id} 보기</button>{' '}
                        {item.parentRevisionId && <button type="button" disabled={busy || !!conflict} onClick={() => void undoRevision(item.id)}>리비전 {item.id} 되돌리기</button>}
                      </li>
                    ))}
                  </ul>
                  <button type="button" disabled={busy || !compareFrom || !compareTo} onClick={() => void compareRevisions()}>선택한 리비전 비교</button>{' '}
                  {nextCursor && <button type="button" disabled={busy} onClick={() => void fetchHistory(nextCursor)}>이력 더 보기</button>}
                </>
              )}
              {comparison && <ComparisonView comparison={comparison} title="리비전 비교" />}
              {revision && revision.id !== conflictRevisionId && (
                <div>
                  <h3>이전 리비전 {revision.id}</h3>
                  <p><time dateTime={revision.createdAt}>{revision.createdAt}</time> · {revision.summary}</p>
                  <pre aria-label="이전 Wikitext 원문">{revision.source}</pre>
                </div>
              )}
            </div>
            <form onSubmit={event => void save(event)}>
              <h3>원문 편집</h3>
              <label>편집할 Wikitext 원문<textarea value={draft} onChange={event => setDraft(event.target.value)} disabled={busy} rows={8} spellCheck={false} /></label>
              <label>편집 요약<input value={summary} onChange={event => setSummary(event.target.value)} disabled={busy} /></label>
              <button disabled={busy || !!conflict}>편집 저장</button>
            </form>
            {conflict && (
              <div>
                {conflict.comparison ? <ComparisonView comparison={conflict.comparison} title="충돌 리비전 비교" /> : <p>리비전 비교를 표시할 수 없습니다. 작성한 원문과 요약은 유지됩니다.</p>}
                {conflictRevisionId && (
                  <>
                    <button type="button" disabled={busy} onClick={() => void fetchRevision(conflictRevisionId)}>충돌 시점 원문 보기</button>
                    {(revision?.id === conflictRevisionId || page.currentRevisionId === conflictRevisionId) && (
                      <pre aria-label="충돌 시점 Wikitext 원문">{revision?.id === conflictRevisionId ? revision.source : page.source}</pre>
                    )}
                    <button type="button" disabled={busy} onClick={() => {
                      setBaseRevisionId(conflictRevisionId)
                      setConflict(undefined)
                      setError('')
                      setStatus('충돌 시점 리비전을 저장 기준으로 선택했습니다. 작성한 원문을 검토한 후 저장하세요.')
                    }}>충돌 리비전을 저장 기준으로 선택</button>
                  </>
                )}
                <button type="button" disabled={busy} onClick={() => { setPage(undefined); clearHistory(); setConflict(undefined); setError(''); setStatus('편집을 취소했습니다.') }}>초안 버리고 편집 취소</button>
              </div>
            )}
          </section>
        )}
      </main>
      <footer>
        <a href="https://github.com/fregmented/LibertyWiki">LibertyWiki 소스 코드 · AGPL-3.0-only</a>
      </footer>
    </>
  )
}

const container = document.getElementById('root')
if (container) createRoot(container).render(<App />)
