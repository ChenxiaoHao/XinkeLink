import { ApiClient, Json, query, requestId } from './client'
import { commentView, media, messageView, noticeView, postView, profileView } from './adapters'
import { LOCAL_STUDENT_ID_KEY } from './config'
import { backendImageId } from './image-urls'
export class BackendService {
  private uploaded = new Map<string, string>()
  constructor(public api: ApiClient) {}
  async all(path: string, args: Json = {}): Promise<Json[]> {
    const items: Json[] = []; let cursor: string | null = null
    do {
      const page = await this.api.request(path + query({ ...args, size: 50, cursor }))
      items.push(...page.items)
      if (page.hasMore && (!page.nextCursor || page.nextCursor === cursor)) throw new Error('分页响应有误，请刷新')
      cursor = page.hasMore ? page.nextCursor : null
    } while (cursor)
    return items
  }
  async images(paths: string[], purpose: string, existing: Json[] = []): Promise<string[]> {
    const result: string[] = []
    for (const path of paths) {
      // 授权地址会变化，编辑保存时仍按图片ID识别已有图片。
      const id = backendImageId(path)
      const known = existing.find(image => media(image) === path || (!!id && String(image.imageId) === id))
      const key = purpose + ':' + path
      if (known) result.push(known.imageId)
      else if (this.uploaded.has(key)) result.push(this.uploaded.get(key)!)
      else { const image = await this.api.upload(path, purpose); this.uploaded.set(key, image.id); result.push(image.id) }
    }
    return result
  }
  async togglePost(id: string, field: string, current: boolean): Promise<Json> {
    return this.api.request('/posts/' + encodeURIComponent(id) + '/' + (field === 'liked' ? 'like' : 'favorite'), current ? 'DELETE' : 'PUT')
  }
  async comments(id: string, expanded: string[], repliesOpen: string[]): Promise<Json[]> {
    const roots = await this.all('/posts/' + encodeURIComponent(id) + '/comments')
    return Promise.all(roots.map(async root => {
      const replies = repliesOpen.includes(root.id) ? await this.all('/comments/' + encodeURIComponent(root.id) + '/replies') : root.replies
      return { ...commentView(root, expanded.includes(root.id)), replyCount: root.replyCount, repliesExpanded: repliesOpen.includes(root.id), replies: replies.map((reply: Json) => commentView(reply, expanded.includes(reply.id))) }
    }))
  }
  async profile(): Promise<Json> {
    const me = await this.api.request('/me'); this.api.setMe(me)
    return profileView(me, String(this.api.runtime.getStorageSync(LOCAL_STUDENT_ID_KEY) || ''))
  }
  async profileSummary(): Promise<Json> {
    const [profile, summary] = await Promise.all([this.profile(), this.api.request('/me/summary')])
    return { profile, postCount:summary.postCount, collectionCount:summary.favoritePostCount, likeCount:summary.receivedLikeCount }
  }
  async publicProfile(id: string): Promise<Json> {
    const [user,posts] = await Promise.all([this.api.request('/users/'+encodeURIComponent(id)),this.all('/users/'+encodeURIComponent(id)+'/posts')])
    const {studentId:_private,...profile}=profileView(user)
    return {...profile,id:user.id,isSelf:user.id===this.api.me?.id,posts:posts.map(postView),likeCount:user.stats.likeCount}
  }
  async collections(): Promise<Json> {
    const posts=await this.all('/me/favorites',{type:'post'})
    return {posts:posts.map(item=>item.item?postView(item.item):{id:item.targetId,content:'帖子已不可用',images:[],authorName:'',avatar:'/assets/icons/user-round-muted.png',collected:true,unavailable:true})}
  }
  async likes(): Promise<Json> {
    const posts=await this.all('/me/likes',{type:'post'})
    return {posts:posts.map(item=>item.item?postView(item.item):{id:item.targetId,content:'帖子已不可用',images:[],authorName:'',avatar:'/assets/icons/user-round-muted.png',liked:true,unavailable:true})}
  }
  async conversation(peerId:string):Promise<Json> {return this.api.request('/conversations','POST',{peerUserId:peerId})}
  conversationView(dto: Json): Json {
    const time = new Date(dto.updatedAt), valid = !!dto.updatedAt && Number.isFinite(time.getTime())
    return { id: dto.id, peer: { ...dto.peer, avatar: media(dto.peer.avatar) || '/assets/icons/user-round-muted.png', bio: dto.peer.bio || '' },
      unread: dto.unreadCount, lastMessage: dto.lastMessage?.visibility === 'hidden' ? '消息已不可用' : dto.lastMessage?.text || '还没有消息',
      updatedAt: Date.parse(dto.updatedAt), timeLabel: valid ? String(time.getHours()).padStart(2, '0') + ':' + String(time.getMinutes()).padStart(2, '0') : '',
      messages: [], version: dto.version }
  }
  async conversations(): Promise<Json[]> { return (await this.all('/conversations')).map(dto => this.conversationView(dto)) }
  async notifications(): Promise<Json[]> { return (await this.all('/notifications')).map(noticeView) }
  async messages(id: string, args: Json = {}): Promise<Json> {
    const page = await this.api.request('/conversations/' + encodeURIComponent(id) + '/messages' + query({ size: 100, ...args }))
    return { ...page, items: page.items.map(messageView) }
  }
  async send(id:string,text:string,clientMessageId=requestId()):Promise<Json> { return this.api.request('/conversations/'+encodeURIComponent(id)+'/messages','POST',{text,clientMessageId}) }
}
