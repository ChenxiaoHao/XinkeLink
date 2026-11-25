import { LocalStorage } from '../profile/repository'
import { readPrivateValue, writePrivateValue } from '../profile/private-storage'
import { emptyPostDraft, PostDraft, validatePostDraft } from './publish'

export const POST_DRAFT_KEY = 'xinkelink.post.draft.v1'
function draftKey(editId: string): string { return editId ? POST_DRAFT_KEY + ':edit:' + encodeURIComponent(editId) : POST_DRAFT_KEY }
export function readPostDraft(storage: LocalStorage, editId = ''): PostDraft | null {
  const raw = readPrivateValue(storage, draftKey(editId))
  if (!raw || typeof raw !== 'object') return null
  const draft = { ...emptyPostDraft(), ...raw as Partial<PostDraft>, images: [] }
  if (validatePostDraft(draft)) return null
  return draft
}
export function savePostDraft(storage: LocalStorage, form: PostDraft, editId = ''): boolean {
  return writePrivateValue(storage, draftKey(editId), { ...form, images: [] })
}
export function clearPostDraft(storage: LocalStorage, editId = ''): boolean {
  return writePrivateValue(storage, draftKey(editId), null)
}
