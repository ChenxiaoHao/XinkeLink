import { PostCategory } from '../forum/types'

/** Link AI 的本地写作助手：只做输入文本的确定性整理，不臆造内容，也不产生任何调用费用。 */
export interface AiReply { text: string; postable: boolean }

const BOARDS: { key: PostCategory; label: string; words: string[] }[] = [
  { key: 'lost-found', label: '失物招领', words: ['丢', '捡', '失物', '招领', '校园卡', '钱包', '钥匙', '水杯', '雨伞'] },
  { key: 'study', label: '学习交流', words: ['课', '考试', '复习', '作业', '笔记', '实验', '论文', '绩点', '保研', '选课'] },
  { key: 'help', label: '校园互助', words: ['求助', '组队', '帮忙', '借', '拼车', '转让', '求带', '找人'] },
  { key: 'campus', label: '校园生活', words: [] },
]

export function tidy(text: string): string {
  return String(text ?? '').replace(/[ \t]+/g, ' ').replace(/\s*\n\s*/g, '\n').trim()
}

function sentences(text: string): string[] {
  const out: string[] = []
  let buffer = ''
  for (const char of tidy(text)) {
    buffer += char
    if ('。！？!?\n'.includes(char)) { if (buffer.trim()) out.push(buffer.trim()); buffer = '' }
  }
  if (buffer.trim()) out.push(buffer.trim())
  return out
}

function clip(text: string, max: number): string {
  const clean = text.trim()
  if (clean.length <= max) return clean
  return clean.slice(0, max).replace(/[，,、；;：:\s]+$/, '') + '…'
}

export function boardFor(text: string): { key: PostCategory; label: string } {
  const source = tidy(text)
  const hit = BOARDS.find(board => board.words.some(word => source.includes(word)))
  const board = hit || BOARDS[BOARDS.length - 1]
  return { key: board.key, label: board.label }
}

function body(text: string): string {
  return tidy(text)
    .replace(/^(帮我|请|麻烦)?(写|发|整理|润色)(一)?(条|个|篇)?/, '')
    .replace(/^[，,、。\s]+/, '')
}

function asPost(text: string): string {
  const content = sentences(body(text)).join('')
  return clip(content || body(text), 140)
}

function titleOptions(text: string): string[] {
  const core = clip(sentences(body(text))[0] || asPost(text), 16).replace(/[。！？…]+$/, '')
  return ['【' + boardFor(text).label + '】' + core, core, core + '，看到麻烦回一下']
}

function polish(text: string): string {
  const clean = sentences(body(text)).join('')
  if (!clean) return asPost(text)
  return /[。！？…]$/.test(clean) ? clean : clean + '。'
}

export function replyTo(prompt: string): AiReply {
  const text = tidy(prompt)
  if (!text) return { text: '写下你想发的内容，我可以帮你整理成一条帖子。', postable: false }
  if (/板块|发到哪|分类/.test(text)) {
    const board = boardFor(text)
    return { text: '从关键词看，这条更适合「' + board.label + '」。发帖时点底部的 # 选它，同板块的同学更容易看到。', postable: false }
  }
  if (/标题/.test(text)) {
    const titles = titleOptions(text)
    return { text: '给你三个标题备选：\n1. ' + titles.join('\n2. ') + '\n\n发帖时把其中一句放在第一行，读起来更像一条动态。', postable: false }
  }
  if (/变短|缩写|精简|总结|提炼|三句话/.test(text)) {
    const short = clip(sentences(body(text)).slice(0, 2).join(''), 80)
    return { text: '压成两句是这样：\n' + short, postable: short.length > 0 }
  }
  if (/润色|改一改|通顺|顺一下/.test(text)) {
    return { text: '顺过一遍：\n' + polish(text), postable: true }
  }
  const board = boardFor(text)
  if (board.key === 'lost-found') {
    return { text: '【失物招领】\n' + asPost(text) + '\n有线索的同学请在评论区回复，也可以私信我，谢谢！', postable: true }
  }
  if (board.key === 'help') {
    return { text: '【校园互助】\n' + asPost(text) + '\n有空的同学可以在评论区回复，我们一起想办法。', postable: true }
  }
  return { text: '整理成一条帖子：\n' + asPost(text) + '\n\n建议发到「' + board.label + '」板块。', postable: true }
}
