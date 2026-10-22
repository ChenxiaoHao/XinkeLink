import { MockPost } from './types'

const image = (name: string): string => '/assets/mock/forum/' + name + '.jpg'
// Fixed relative to app startup, so recommendation and latest orders are stable.
const startedAt = Date.now()
const author = (id: string, nickname: string, avatar = 'avatar-lin'): MockPost['author'] => ({
  id, nickname, avatar: image(avatar),
})
const minutesAgo = (minutes: number): number => startedAt - minutes * 60_000

export const MOCK_POSTS: readonly MockPost[] = [
  {
    id: 'post-001', category: 'campus', author: author('mock-lin', '林知夏'),
    createdAt: minutesAgo(15), recommendation: 100,
    content: '今天在图书馆五楼东区靠窗拍到的晚霞，金黄与紫蓝交织，风也很温柔。期末周大家一起加油呀，附上复习状态～',
    images: [image('campus'), image('desk'), image('campus-green'), image('library')],
    likes: 28, comments: 12, initiallyCollected: true,
  },
  {
    id: 'post-002', category: 'lost-found',
    author: author('private-author-02', '不应公开的模拟作者', 'avatar-zhou'), anonymousNumber: '023',
    createdAt: minutesAgo(60), recommendation: 99,
    content: '在教学楼 C 栋 302 阶梯教室第二排捡到一个灰色 iPad 保护套，内附一只触控笔，失主请在评论区联系我或私信核对特征。',
    images: [image('tablet')], likes: 8, comments: 4,
  },
  {
    id: 'post-003', category: 'study', author: author('mock-zhou', '周同学', 'avatar-zhou'),
    createdAt: minutesAgo(35), recommendation: 98,
    content: '把这周的高数笔记整理好了。先梳理公式，再做错题，复习起来清楚多了。大家也可以分享自己的学习方法。',
    images: [image('desk'), image('book')], likes: 16, comments: 6,
  },
  {
    id: 'post-004', category: 'help', author: author('mock-su', '苏小禾'),
    createdAt: minutesAgo(48), recommendation: 97,
    content: '请问图书馆周末几点开门呀？想早点过去复习，也祝这周有考试的同学都顺顺利利！',
    images: [], likes: 6, comments: 3,
  },
  {
    id: 'post-005', category: 'campus', author: author('mock-xu', '许小满'),
    createdAt: minutesAgo(85), recommendation: 96,
    content: '课后慢慢走了一圈，把校园里的小片刻存起来。忙着赶路的时候，也记得抬头看看呀。',
    images: [image('campus-green'), image('library'), image('campus'), image('book'), image('desk'), image('campus-green')],
    likes: 32, comments: 7,
  },
  {
    id: 'post-006', category: 'lost-found', author: author('mock-lu', '陆一帆', 'avatar-zhou'),
    createdAt: minutesAgo(120), recommendation: 95,
    content: '在操场看台附近捡到一串钥匙，挂着蓝色的小挂件。已经交给值班处了，丢失的同学记得去核对领取。',
    images: [], likes: 11, comments: 2,
  },
  {
    id: 'post-007', category: 'study', author: author('mock-tang', '唐果'),
    createdAt: minutesAgo(95), recommendation: 94,
    content: '今天的阅读和摘抄完成啦。每天留一点时间给自己，慢慢积累也会有收获。',
    images: [image('book'), image('desk'), image('library')], likes: 19, comments: 5,
  },
  {
    id: 'post-008', category: 'help', author: author('mock-chen', '陈同学', 'avatar-zhou'),
    createdAt: minutesAgo(150), recommendation: 93,
    content: '想找一个安静的位置做小组作业，图书馆的讨论空间需要提前预约吗？有了解的同学可以说说吗，谢谢大家。',
    images: [image('library')], likes: 4, comments: 8,
  },
  {
    id: 'post-009', category: 'campus', author: author('mock-bai', '白日小记'),
    createdAt: minutesAgo(3), recommendation: 92,
    content: '下课后的校园，连空气都慢了下来。分享今天路上遇到的两处风景。',
    images: [image('campus'), image('campus-green')], likes: 3, comments: 1,
  },
  {
    id: 'post-010', category: 'lost-found', author: author('mock-fang', '方同学', 'avatar-zhou'),
    createdAt: minutesAgo(180), recommendation: 91,
    content: '昨天自习结束后落下了一本书，封面内页有手写名字。如果有同学看到，麻烦留言联系，谢谢！配图是当时自习的位置。',
    images: [image('book'), image('library'), image('desk'), image('book')], likes: 5, comments: 2,
  },
  {
    id: 'post-011', category: 'study', author: author('mock-wen', '温言'),
    createdAt: minutesAgo(210), recommendation: 90,
    content: '今天完成了一个小练习，把不懂的地方逐个记下来再解决。学习路上一起加油。',
    images: [image('desk')], likes: 12, comments: 3,
  },
  {
    id: 'post-012', category: 'help', author: author('mock-qing', '晴天'),
    createdAt: minutesAgo(240), recommendation: 89,
    content: '有没有同学知道学校附近哪里可以打印装订？课程报告快整理完了，想找个方便的地方。',
    images: [], likes: 2, comments: 4,
  },
]
