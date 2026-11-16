// SPDX-License-Identifier: AGPL-3.0-only
import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'

test('undoes the latest revision as a new revision and preserves a draft', async ({ page, request }) => {
  const token = process.env.WIKI_BROWSER_TOKEN
  if (!token) throw new Error('Set WIKI_BROWSER_TOKEN to the server operator token.')
  const headers = { Authorization: `Bearer ${token}` }
  const title = `Browser undo ${randomUUID()}`
  const created = await request.post('/api/v1/pages', { headers, data: { title, namespaceId: 0, source: 'original' } })
  expect(created.status()).toBe(201)
  const initial = await created.json()
  const edited = await request.post(`/api/v1/pages/${initial.id}/revisions`, {
    headers, data: { baseRevisionId: initial.currentRevisionId, source: 'changed' },
  })
  expect(edited.status()).toBe(201)
  const head = await edited.json()

  await page.goto('/')
  await page.getByLabel('운영자 토큰').fill(token)
  await page.getByLabel('조회할 제목').fill(title)
  await page.getByRole('button', { name: '원문 조회' }).click()
  await page.getByLabel('편집할 Wikitext 원문').fill('my draft')
  await page.getByLabel('편집 요약', { exact: true }).fill('my summary')
  await page.getByRole('button', { name: '문서 이력 보기' }).click()
  await expect(page.getByRole('button', { name: `리비전 ${initial.currentRevisionId} 되돌리기` })).toHaveCount(0)
  await page.getByRole('button', { name: `리비전 ${head.currentRevisionId} 되돌리기` }).click()
  await expect(page.getByLabel('현재 Wikitext 원문')).toHaveText('original')
  await expect(page.getByLabel('편집할 Wikitext 원문')).toHaveValue('my draft')
  await expect(page.getByLabel('편집 요약', { exact: true })).toHaveValue('my summary')
  await expect(page.getByRole('status')).toContainText('되돌렸습니다')
  const history = await request.get(`/api/v1/pages/${initial.id}/revisions`, { headers })
  const revisions = (await history.json()).revisions
  expect(revisions[0].id).not.toBe(head.currentRevisionId)
  expect(revisions[0].parentRevisionId).toBe(head.currentRevisionId)
  await expect(page.getByRole('button', { name: `리비전 ${revisions[0].id} 되돌리기` })).toBeVisible()
  await expect(page.getByRole('button', { name: '편집 저장' })).toBeDisabled()
  await page.getByRole('button', { name: '충돌 리비전을 저장 기준으로 선택' }).click()
  await expect(page.getByRole('button', { name: '편집 저장' })).toBeEnabled()
})

test('reports a stale undo without discarding the draft', async ({ page, request }) => {
  const token = process.env.WIKI_BROWSER_TOKEN
  if (!token) throw new Error('Set WIKI_BROWSER_TOKEN to the server operator token.')
  const headers = { Authorization: `Bearer ${token}` }
  const title = `Browser stale undo ${randomUUID()}`
  const created = await request.post('/api/v1/pages', { headers, data: { title, namespaceId: 0, source: 'original' } })
  const initial = await created.json()
  const edited = await request.post(`/api/v1/pages/${initial.id}/revisions`, { headers, data: { baseRevisionId: initial.currentRevisionId, source: 'changed' } })
  const head = await edited.json()
  await page.goto('/')
  await page.getByLabel('운영자 토큰').fill(token)
  await page.getByLabel('조회할 제목').fill(title)
  await page.getByRole('button', { name: '원문 조회' }).click()
  await page.getByLabel('편집할 Wikitext 원문').fill('draft')
  await page.getByRole('button', { name: '문서 이력 보기' }).click()
  const competing = await request.post(`/api/v1/pages/${initial.id}/revisions`, { headers, data: { baseRevisionId: head.currentRevisionId, source: 'newer' } })
  expect(competing.status()).toBe(201)
  await page.getByRole('button', { name: `리비전 ${head.currentRevisionId} 되돌리기` }).click()
  await expect(page.getByRole('alert')).toContainText('최신 이력을 확인')
  await expect(page.getByLabel('편집할 Wikitext 원문')).toHaveValue('draft')
  await expect(page.getByRole('button', { name: `리비전 ${head.currentRevisionId} 되돌리기` })).toHaveCount(0)
  await page.getByRole('button', { name: '원문 조회' }).click()
  await expect(page.getByLabel('편집할 Wikitext 원문')).toHaveValue('draft')
  await expect(page.getByRole('button', { name: '편집 저장' })).toBeDisabled()
})

test('undoes an older edit while preserving later source and rejects an overlapping edit', async ({ page, request }) => {
  const token = process.env.WIKI_BROWSER_TOKEN
  if (!token) throw new Error('Set WIKI_BROWSER_TOKEN to the server operator token.')
  const headers = { Authorization: `Bearer ${token}` }
  const title = `Browser historical undo ${randomUUID()}`
  const created = await request.post('/api/v1/pages', {
    headers, data: { title, namespaceId: 0, source: 'original\nseparator\nlast' },
  })
  expect(created.status()).toBe(201)
  const initial = await created.json()
  const path = `/api/v1/pages/${initial.id}`
  const edited = await request.post(`${path}/revisions`, {
    headers, data: { baseRevisionId: initial.currentRevisionId, source: 'changed\nseparator\nlast' },
  })
  expect(edited.status()).toBe(201)
  const target = await edited.json()
  const later = await request.post(`${path}/revisions`, {
    headers, data: { baseRevisionId: target.currentRevisionId, source: 'changed\nseparator\nlater' },
  })
  expect(later.status()).toBe(201)
  const head = await later.json()

  await page.goto('/')
  await page.getByLabel('운영자 토큰').fill(token)
  await page.getByLabel('조회할 제목').fill(title)
  await page.getByRole('button', { name: '원문 조회' }).click()
  await page.getByRole('button', { name: '문서 이력 보기' }).click()
  await page.getByRole('button', { name: `리비전 ${target.currentRevisionId} 되돌리기` }).click()
  await expect(page.getByLabel('현재 Wikitext 원문')).toHaveText('original\nseparator\nlater')
  await expect(page.getByLabel('편집할 Wikitext 원문')).toHaveValue('original\nseparator\nlater')
  const history = (await (await request.get(`${path}/revisions`, { headers })).json()).revisions
  expect(history[0].parentRevisionId).toBe(head.currentRevisionId)
  expect(history).toHaveLength(4)

  // Retrying the already reversed historical change must not erase the current source.
  await page.getByLabel('편집할 Wikitext 원문').fill('draft retained')
  await page.getByRole('button', { name: `리비전 ${target.currentRevisionId} 되돌리기` }).click()
  await expect(page.getByRole('alert')).toContainText('최신 이력을 확인')
  await expect(page.getByLabel('편집할 Wikitext 원문')).toHaveValue('draft retained')
  const after = await request.get(path, { headers })
  expect((await after.json()).currentRevisionId).toBe(history[0].id)
})
