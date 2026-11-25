import { getViewerRole } from '../features/profile/viewer'
import { readSearchHistory } from '../features/search/history'
import { readPostDraft, savePostDraft, clearPostDraft } from '../features/forum/drafts'
// 必须排在其它导入之前：先确定是否处于本地演示模式，再决定页面走哪套实现。
import './demo-mode'
import { ApiError, client, Json, query, requestId, Runtime } from './client'
import { media, mergeMessages, messageView, postView, profileView } from './adapters'
import { BackendService } from './service'
import { Realtime } from './realtime'
import { LOCAL_STUDENT_ID_KEY } from './config'
import { PageImageRenewal, loadAuthorizedLocalImage, needsPreviewRefresh, sessionAvatar } from './preview'
import { backendImageId } from './image-urls'
import { chatTimeLabel } from '../utils/presentation'
import { menuRightSpace } from '../utils/layout'
import { detailVisit } from '../features/statistics/views'
type NativeRuntime = typeof wx

/** Preserve the existing native view and pure UI handlers; replace every business operation.
 * Historical synchronous behavior is selected only by an explicit test harness flag. */
export function registerPage(runtime: Runtime, nativePage: typeof Page, name: string): typeof Page {
  return ((fixture: Json) => {
    if (runtime.__fixtureMode === true) { nativePage(fixture); return }
    const api = client(runtime), service = new BackendService(api), wx = runtime as NativeRuntime
    const toast = (error: unknown): void => { wx.showToast({ title: error instanceof Error ? error.message : '操作未完成，请重试', icon: 'none' }) }
    const navigate = (page: string, id: string): void => { wx.navigateTo({ url: '/pages/' + page + '/index?id=' + encodeURIComponent(id) }) }
    const showSelfAvatar = (page: Json): void => {
      const prepared = sessionAvatar(api)
      page.setData({ selfAvatar: prepared.url })
      void prepared.renewal?.then(avatar => {
        if (avatar && !page._unloaded && page.data.selfAvatar === prepared.url) page.setData({ selfAvatar: avatar })
      })
    }
    const confirm = (title: string, content: string): Promise<boolean> => new Promise(resolve => wx.showModal({ title, content, success: result => resolve(result.confirm), fail: () => resolve(false) }))
    const safe = async (page: any, action: () => Promise<void>, busy?: string): Promise<void> => {
      if (busy && page.data[busy]) return
      if (busy) page.setData({ [busy]: true })
      try { await action() } catch (error) { toast(error) } finally { if (busy) page.setData({ [busy]: false }) }
    }
    const interaction = async (page: any, id: string, field: string, posts: Json[]): Promise<void> => {
      const generation = page._requestGeneration
      // 服务端还没有转发接口（posts/:id/repost），不能把它退回成收藏写请求。
      if (field === 'reposted') { wx.showToast({ title: '转发功能即将上线', icon: 'none' }); return }
      const post = posts.find(item => item.id === id); if (!post) return
      try { if (!await api.requireSession()) return } catch (error) { toast(error); return }
      const userId = api.me?.id
      if (name === 'search' && (generation !== page._requestGeneration || !page.data.postResults.includes(post))) return
      await safe(page, async () => {
        const result = await service.togglePost(id, field, field === 'liked' ? post.liked : post.collected)
        if (!api.loggedIn || api.me?.id !== userId || name === 'search' && generation !== page._requestGeneration) return
        const change = field === 'liked' ? { liked: result.liked, likeCount: result.likeCount } : { collected: result.favorited }
        // 使用服务端返回的状态，只更新当前视图，不重置分页或滚动位置。
        if (name === 'forum-detail') {
          if (page.data.post?.id === id) page.setData({ post: { ...page.data.post, ...change } })
        } else if (name === 'user-home') {
          const index = page.data.profile?.posts.findIndex((item: Json) => item.id === id) ?? -1
          if (index >= 0) page.setData({ ['profile.posts[' + index + ']']: { ...page.data.profile.posts[index], ...change } })
        } else if (name === 'profile') {
          // 个人主页两个分栏都可能在展示这条帖子，按实际所在分栏就地更新。
          for (const key of ['posts', 'likedPosts']) {
            const list: Json[] = page.data[key] || []
            const index = list.findIndex((item: Json) => item.id === id)
            if (index < 0) continue
            const removed = key === 'likedPosts' && field === 'liked' && !result.liked
            page.setData(removed ? { likedPosts: list.filter((item: Json) => item.id !== id) } : { [key + '[' + index + ']']: { ...list[index], ...change } })
          }
        } else if (name === 'search') {
          const index = page.data.postResults.findIndex((item: Json) => item.id === id)
          if (index >= 0) page.setData({ ['postResults[' + index + ']']: { ...page.data.postResults[index], ...change } })
        } else {
          const index = page.data.posts.findIndex((item: Json) => item.id === id)
          if (index < 0) return
          if (name === 'my-collections' && field === 'liked' && !result.liked) page.setData({ posts: page.data.posts.filter((item: Json) => item.id !== id) })
          else page.setData({ ['posts[' + index + ']']: { ...page.data.posts[index], ...change } })
        }
      }, 'mutating')
    }
    const guestProfile = (): Json => ({ profile: profileView({ nickname: '登录' }), posts: [], likedPosts: [], activeTab: 'posts', loggedIn: false, postCount: 0, collectionCount: 0, likeCount: 0, loadFailed: false })
    const loginHere = (page: any): Promise<void> => safe(page, async () => {
      if (!await api.requireSession()) return
      await page.reload()
    }, 'loggingIn')
    const openProtected = async (page: any, url: string): Promise<void> => {
      if (!await api.requireSession()) return
      await page.reload(); wx.navigateTo({ url })
    }
    const pageData: Json = { loadFailed: false, loggingIn: false, authVisible: false, authSaving: false, authError: '', _cursor: null }
    const common: Json = {
      _posts: [], _requestGeneration: 0, _cursor: null,
      async onRemoteAvatarError(event: Json) {
        const { kind, id } = event.currentTarget.dataset
        const current = (): string | undefined => {
          if (kind === 'profile') return this.data.profile?.avatar
          if (kind === 'self') return this.data.selfAvatar
          if (kind === 'peer') return this.data.peer?.avatar
          if (kind === 'conversation') return this.data.conversations?.find((item: Json) => item.id === id)?.peer?.avatar
          if (kind === 'notice') return this.data.visibleNotifications?.find((item: Json) => item.id === id)?.avatar
          return undefined
        }
        const source = event.currentTarget.dataset.source || current(), fallback = '/assets/icons/user-round-muted.png'
        if (!source || source === fallback || current() !== source) return
        let avatar = fallback
        try { if (needsPreviewRefresh(runtime, [source])) avatar = await loadAuthorizedLocalImage(runtime, source) } catch { /* 展示默认头像。 */ }
        if (current() !== source) return
        const patch: Json = {}
        if (kind === 'profile') patch['profile.avatar'] = avatar
        if (kind === 'self') patch.selfAvatar = avatar
        if (kind === 'peer') patch['peer.avatar'] = avatar
        if (kind === 'conversation') {
          const index = this.data.conversations.findIndex((item: Json) => item.id === id)
          if (index >= 0) patch['conversations[' + index + '].peer.avatar'] = avatar
        }
        if (kind === 'notice') for (const list of ['visibleNotifications', 'notifications']) {
          const index = this.data[list]?.findIndex((item: Json) => item.id === id && item.avatar === source) ?? -1
          if (index >= 0) patch[list + '[' + index + '].avatar'] = avatar
        }
        if (Object.keys(patch).length) this.setData(patch)
      },
      async onPullDownRefresh() { try { await this.reload?.() } finally { wx.stopPullDownRefresh() } },
      onRetryTap() { return this.reload?.() },
      onShow() { return this.reload?.() },
      onUnload() { this._authCancelled = true; this._authConsentResolve?.(false); this._authConsentResolve = null; this._disposeSession?.(); this._disposeSession = null },
      requestProfileConsent() {
        this._authCancelled = false
        this.setData({ authVisible: true, authSaving: false, authError: '' })
        this.getTabBar?.()?.setData({ publishVisible: false })
        return new Promise<boolean>(resolve => { this._authConsentResolve = resolve })
      },
      onProfileAuthCancel() {
        if (this.data.authSaving) return
        this.setData({ authVisible: false, authError: '' })
        this._authConsentResolve?.(false); this._authConsentResolve = null
      },
      async onProfileAuthConfirm(event: Json) {
        if (!this.data.authVisible || this.data.authSaving) return
        const nickname = typeof event.detail?.nickname === 'string' ? event.detail.nickname.trim() : ''
        const avatarPath = event.detail?.avatarPath
        if (!nickname || nickname.length > 20 || typeof avatarPath !== 'string' || !avatarPath) { toast(new Error('请选择头像并填写昵称')); return }
        this.setData({ authSaving: true, authError: '' })
        try {
          await api.login(true)
          if (this._authCancelled) { await api.logout(); return }
          const image = await api.upload(avatarPath, 'avatar'), current = await api.request('/me')
          if (this._authCancelled) { await api.logout(); return }
          const updated = await api.request('/me', 'PATCH', { nickname, avatarImageId: image.id, expectedVersion: current.version })
          if (this._authCancelled) { await api.logout(); return }
          api.setMe(updated, true)
          this.setData({ authVisible: false })
          this._authConsentResolve?.(true); this._authConsentResolve = null
        } catch (error) {
          // 资料未保存成功时不把这次确认流程伪装成完成，可保留弹层重试或取消。
          try { if (api.token) await api.logout() } catch { api.clear() }
          this.setData({ authError: error instanceof Error ? error.message : '头像昵称保存失败，请重试' })
        } finally { this.setData({ authSaving: false }) }
      },
      onLoginTap() { return loginHere(this) },
      onLikePost(event: Json) { return (this.savePostInteraction || this.saveInteraction).call(this, event.detail.id, 'liked') },
      onCollectPost(event: Json) { return (this.savePostInteraction || this.saveInteraction).call(this, event.detail.id, 'collected') },
      onRepostPost(event: Json) { return (this.savePostInteraction || this.saveInteraction).call(this, event.detail.id, 'reposted') },
      onOpenPost(event: Json) { wx.navigateTo({ url: '/pages/forum-detail/index?id=' + encodeURIComponent(event.detail.id) }) },
    }
    const detailViewer = () => getViewerRole(wx) === 'guest' ? 'guest' : String(api.me?.id)
    const definitions: Record<string, Json> = {
      forum: {
        data: { posts: [], announcement: '', hasMore: false, feedPageFailed: false },
        onLoad(options: Json = {}) { this.setData({ focusPostId: options.postId || '' }); this._initialLoadPromise = this.reload() },
        async onShow() { this.getTabBar()?.setData({ activeKey: 'forum', publishVisible: false }); if (this._initialLoadPromise) { const initial = this._initialLoadPromise; this._initialLoadPromise = null; await initial; return }; await this.reload() },
        async reload(append = false) {
          if (append && (this._feedLoading || !this._cursor)) return
          const generation = ++this._requestGeneration
          const current = () => generation === this._requestGeneration && !this._unloaded
          const cursor = append ? this._cursor : undefined
          if (!append) this._cursor = null
          this._feedLoading = true
          try {
            if (this.data.timeline === 'following') { this.setData({ posts: [], hasMore: false, loadFailed: false, feedPageFailed: false }); return }
            const category = this.data.activeCategory
            const response = await api.request('/posts' + query({ category: ['recommended', 'latest'].includes(category) ? undefined : category.replace('-', '_'), sort: category === 'latest' ? 'latest' : 'recommended', size: 20, cursor }))
            if (!current()) return
            if (response.hasMore && (!response.nextCursor || response.nextCursor === cursor)) throw new Error('分页响应有误，请重试')
            const patch: Json = { hasMore: response.hasMore, loadFailed: false, feedPageFailed: false }
            if (append) {
              const existing = new Set(this.data.posts.map((post: Json) => post.id))
              let index = this.data.posts.length
              for (const item of response.items) {
                const post = postView(item)
                if (existing.has(post.id)) continue
                existing.add(post.id)
                patch['posts[' + index++ + ']'] = post
              }
            } else patch.posts = response.items.map(postView)
            this.setData(patch)
            this._cursor = response.nextCursor
            this._posts = this.data.posts
          } catch (error) {
            if (current()) {
              this.setData(append ? { feedPageFailed: true } : { hasMore: false, loadFailed: true, feedPageFailed: false })
              toast(error)
            }
            return
          } finally {
            if (current()) this._feedLoading = false
          }
          if (!append) {
            try { const notices = await api.request('/announcements?scope=forum&size=1'); this._announcement = notices.items[0] || null; this.setData({ announcement: this._announcement?.title || '暂无平台公告' }) }
            catch { this._announcement = null; this.setData({ announcement: '公告暂时无法加载' }) }
          }
        },
        replaceFeed() { return this.reload() },
        onReachBottom() { if (this.data.hasMore) return this.reload(true); return Promise.resolve() },
        saveInteraction(id: string, field: string) { return interaction(this, id, field, this.data.posts) },
        onAnnouncementTap() { return safe(this, async () => { if (!this._announcement) return; const item = await api.request('/announcements/' + this._announcement.id); wx.showModal({ title: item.title, content: item.body, showCancel: false }) }) },
      },
      'forum-detail': {
        _expanded: [], _openedReplies: [],
        async onLoad(options: Json = {}) {
          this._postId = options.id || ''
          this._viewVisit = detailVisit(runtime, this._postId)
          // 回复栏左侧展示自己的头像；未登录时留空占位。
          showSelfAvatar(this)
          const initial = { viewer: detailViewer(), pending: this.reload() }
          this._initialDetailLoad = initial
          await initial.pending
          if (options.reply === '1' && this.data.post && !this._unloaded && detailViewer() === initial.viewer) this.setData({ composerOpen: true })
        },
        async onShow() {
          this._detailVisible = true
          const initial = this._initialDetailLoad
          this._initialDetailLoad = null
          if (!initial || initial.viewer !== detailViewer()) return this.reload()
          await initial.pending
          if (initial.viewer === detailViewer()) this.reportDisplayedDetail()
        },
        onHide() { this._detailVisible = false },
        reportDisplayedDetail() {
          if (this._detailVisible && !this._unloaded && this.data.post && this._detail?.viewReportingEnabled === true) {
            this._viewVisit?.displayed(this._detail.permissions?.canComment === true)
          }
        },
        async reload() {
          if (!this._postId) return
          const generation = ++this._requestGeneration
          const viewer = detailViewer()
          try {
            const [post, comments] = await Promise.all([api.request('/posts/' + encodeURIComponent(this._postId)), service.comments(this._postId, this._expanded, this._openedReplies)])
            if (generation !== this._requestGeneration || viewer !== detailViewer()) return
            // 「按热度 / 按时间」在真机侧同样生效：热度 = 本层点赞 + 下层点赞，同分保持服务端时间序。
            if (this.data.commentOrder === 'hot') {
              const score = (thread: Json) => (Number(thread.likeCount) || 0)
                + (thread.replies || []).reduce((sum: number, reply: Json) => sum + (Number(reply.likeCount) || 0), 0)
              comments.sort((a: Json, b: Json) => score(b) - score(a))
            }
            this._detail = post; this.setData({ post: postView(post), comments, loadFailed: false })
            this.reportDisplayedDetail()
          } catch (error) { if (generation === this._requestGeneration && viewer === detailViewer()) { this.setData({ post: null, loadFailed: true }); toast(error) } }
        },
        saveInteraction(field: string) { return interaction(this, this._postId, field, this.data.post ? [this.data.post] : []) },
        onLikeTap() { return this.saveInteraction('liked') }, onCollectTap() { return this.saveInteraction('collected') },
        onRepostPost() { return this.saveInteraction('reposted') },
        onReplyTap(event: Json) { const id = event.detail?.id || event.currentTarget.dataset.id; const target = this.data.comments.flatMap((root: Json) => [root, ...root.replies]).find((item: Json) => item.id === id); if (target) this.setData({ composerOpen: true, sendFailed: false, replyTarget: { id, name: target.authorName } }) },
        async onSubmitComment() {
          try { if (!await api.requireSession()) return } catch (error) { toast(error); return }
          if (!this.data.post || !this.data.commentDraft.trim()) return Promise.resolve()
          if (this.data.commentDraft.trim().length > 1000) { toast(new Error('评论需为1至1000字')); return }
          return safe(this, async () => {
            const text = this.data.commentDraft.trim(), target = this.data.replyTarget?.id
            const signature = text + '|' + (target || '')
            if (this._commentSignature !== signature) { this._commentSignature = signature; this._commentRequestId = requestId() }
            // Only an attempted submission failure belongs to the inline error state;
            // safe still owns the original toast and sending lock.
            try { await api.request('/posts/' + this._postId + '/comments', 'POST', { content: text, ...(target ? { replyToCommentId: target } : {}), clientRequestId: this._commentRequestId }) }
            catch (error) { this.setData({ sendFailed: !(error instanceof ApiError && [400, 401, 403, 404, 409, 422].includes(error.status)) }); throw error }
            this._commentSignature = ''; this.setData({ commentDraft: '', replyTarget: null, composerOpen: false, sendFailed: false, keyboardHeight: 0 }); wx.hideKeyboard?.(); await this.reload(); wx.showToast({ title: '评论已发布', icon: 'success' })
          }, 'sending')
        },
        async onCommentLike(event: Json) {
          if (!await api.requireSession()) return Promise.resolve()
          const id = event.detail?.id || event.currentTarget.dataset.id
          const target = this.data.comments.flatMap((root: Json) => [root, ...root.replies]).find((item: Json) => item.id === id)
          return safe(this, async () => {
            if (!target) return
            const result = await api.request('/comments/' + encodeURIComponent(id) + '/like', target.liked ? 'DELETE' : 'PUT')
            const rootIndex = this.data.comments.findIndex((root: Json) => root.id === id || root.replies.some((reply: Json) => reply.id === id))
            if (rootIndex < 0) return
            const root = this.data.comments[rootIndex], replyIndex = root.replies.findIndex((reply: Json) => reply.id === id)
            const path = 'comments[' + rootIndex + ']' + (replyIndex < 0 ? '' : '.replies[' + replyIndex + ']')
            const item = replyIndex < 0 ? root : root.replies[replyIndex]
            this.setData({ [path]: { ...item, liked: result.liked, likeCount: result.likeCount } })
          }, 'mutating')
        },
        async onDeleteComment(event: Json) {
          if (!await api.requireSession()) return Promise.resolve()
          const id = event.detail?.id || event.currentTarget.dataset.id
          const target = this.data.comments.flatMap((root: Json) => [root, ...root.replies]).find((item: Json) => item.id === id)
          return safe(this, async () => { if (!target?.isMine || !await confirm('删除这条评论？', '删除后无法恢复，一级评论的回复也会移除。')) return; await api.request('/comments/' + id, 'DELETE', undefined, { 'If-Match': '"' + target.version + '"' }); await this.reload() }, 'deleting')
        },
      },
      'forum-publish': {
        async onLoad(options: Json = {}) {
          try {
            const info = wx.getWindowInfo(), menu = wx.getMenuButtonBoundingClientRect(), statusHeight = info.statusBarHeight || 20
            const valid = menu.height > 0 && menu.top >= statusHeight
            // 顶栏整行 padding-top 依赖 headerTop，真机侧也要按胶囊实际位置算，否则会与胶囊重叠。
            this.setData({ statusHeight, rightSpace: valid ? menuRightSpace(info.windowWidth, menu.left) : 104, headerTop: valid ? menu.bottom + 8 : statusHeight + 50 })
          } catch { /* 取不到系统几何时保持默认值。 */ }
          showSelfAvatar(this)
          this.setData({ selfNickname: api.me?.nickname || '' })
          this._editId = options.editId || ''; this.setData({ editId: this._editId })
          if (getViewerRole(wx) === 'guest') { this.setData({ form: { content: '', category: 'campus', anonymous: false, images: [] }, loadFailed: true }); return }
          if (this._editId) {
            try {
              this._detail = await api.request('/posts/' + encodeURIComponent(this._editId))
              if (!this._detail.isMine || getViewerRole(wx) === 'guest') throw new Error('不能编辑此帖子')
              const draft = options.draft === '1' ? readPostDraft(wx, this._editId) : null
              this.setData({ form: { content: draft?.content ?? this._detail.content, category: draft?.category ?? this._detail.category.replace('_', '-'), anonymous: this._detail.author.isAnonymous, images: this._detail.images.map((image: Json) => media(image)) } })
            } catch (error) { this.setData({ loadFailed: true }); toast(error) }
          } else if (options.draft === '1') {
            try { const draft = readPostDraft(wx); if (draft) this.setData({ form: draft }) } catch { toast(new Error('草稿读取失败')) }
          }
          // 编辑态的分类来自服务端，标签要跟着同步，否则 chip 会停在默认板块。
          if (this.data.form.category === 'study') this.setData({ categories: [...this.data.categories, { key: 'study', label: '学习交流（历史）', short: '学习交流' }] })
          const categories = this.data.categories as Json[], index = categories.findIndex((item: Json) => item.key === this.data.form.category)
          if (index >= 0) this.setData({ categoryIndex: index, categoryLabel: categories[index].label })
        },
        onShow() { if (getViewerRole(wx) === 'guest') this.setData({ form: { content: '', category: 'campus', anonymous: false, images: [] }, selfNickname: '', selfAvatar: '', loadFailed: true }) },
        async onSaveDraft() { return safe(this, async () => { if (!await api.requireSession() || this.data.loadFailed || this.data.publishing || this._published) return; if (savePostDraft(wx, this.data.form, this._editId)) wx.showToast({ title: '文字草稿已保存', icon: 'success' }) }) },
        async onPublishTap() {
          if (!await api.requireSession() || this.data.loadFailed || !this.data.form.content.trim()) return Promise.resolve()
          return safe(this, async () => {
            const form = this.data.form, imageIds = await service.images(form.images, 'post', this._detail?.images)
            const body = { content: form.content.trim(), category: form.category.replace('-', '_'), imageIds }
            const signature = JSON.stringify(body)
            if (this._publishSignature !== signature) { this._publishSignature = signature; this._publishRequestId = requestId() }
            const post = await api.request(this._editId ? '/posts/' + this._editId : '/posts', this._editId ? 'PATCH' : 'POST', this._editId ? { ...body, expectedVersion: this._detail.version } : { ...body, anonymous: form.anonymous, clientRequestId: this._publishRequestId })
            this._published = true; try { clearPostDraft(wx, this._editId) } catch { toast(new Error('内容已保存，草稿清理失败')) }; wx.showToast({ title: this._editId ? '修改已保存' : '发布成功', icon: 'success' }); wx.redirectTo({ url: '/pages/forum-detail/index?id=' + encodeURIComponent(post.id) })
          }, 'publishing')
        },
      },
      search: {
        data: {postHasMore:false,postPageFailed:false,loadingType:''},
        resetSearch() {this._requestGeneration++;this._searchTerm='';this._searchPage=null;this._posts=[];this.setData({hasSearched:false,searching:false,searchFailed:false,postResults:[],postHasMore:false,postPageFailed:false,loadingType:''})},
        onInput(event:Json) {this.resetSearch();this.setData({query:event.detail.value.slice(0,60)})},
        onClearInput() {this.resetSearch();this.setData({query:'',inputFocused:true})},
        async runSearch(value?:string) {
          const q=(value??this.data.query).trim();this.resetSearch();if(!q)return
          const generation=this._requestGeneration;this._searchTerm=q;this.setData({hasSearched:true,searching:true})
          try {const response=await api.request('/search'+query({q,type:'posts',size:20}));if(generation!==this._requestGeneration)return;const result=response.result;if(result.hasMore&&!result.nextCursor)throw new Error('分页响应有误，请重试');this._searchPage=result;this._posts=result.items.map(postView);this.setData({postResults:this._posts,searching:false,postHasMore:!!result.hasMore})}
          catch(error){if(generation===this._requestGeneration){this.setData({searching:false,searchFailed:true});toast(error)}}
        },
        async loadMoreSearch(){
          const state=this._searchPage;if(!state?.hasMore||!state.nextCursor||this.data.searching||this.data.loadingType)return
          const generation=this._requestGeneration;this.setData({loadingType:'posts',postPageFailed:false})
          try {const response=await api.request('/search'+query({q:this._searchTerm,type:'posts',size:20,cursor:state.nextCursor}));if(generation!==this._requestGeneration)return;const result=response.result;if(result.hasMore&&(!result.nextCursor||result.nextCursor===state.nextCursor))throw new Error('分页响应有误，请重试');const ids=new Set(this.data.postResults.map((p:Json)=>p.id));const incoming=result.items.map(postView).filter((p:Json)=>{if(ids.has(p.id))return false;ids.add(p.id);return true});this._searchPage=result;this._posts=[...this.data.postResults,...incoming];this.setData({postResults:this._posts,postHasMore:!!result.hasMore})}
          catch(error){if(generation===this._requestGeneration){this.setData({postPageFailed:true});toast(error)}}finally{if(generation===this._requestGeneration)this.setData({loadingType:''})}
        },
        onReachBottom(){return this.loadMoreSearch()},onLoadMoreTap(){return this.loadMoreSearch()},
        onShow(){this.getTabBar()?.setData({activeKey:'search'});this.setData({history:readSearchHistory(wx)});this.readHot()},
        savePostInteraction(id:string,field:string){return interaction(this,id,field,this.data.postResults)},reload(){return this.runSearch()},
      },
      profile: {
        data: guestProfile(),
        onShow() {
          this.getTabBar()?.setData({ activeKey: 'profile', publishVisible: false })
          // 封面导航浮层要避让状态栏和微信胶囊。
          try {
            const info = wx.getWindowInfo(), menu = wx.getMenuButtonBoundingClientRect(), statusHeight = info.statusBarHeight || 20
            const valid = menu.height > 0 && menu.top >= statusHeight
            this.setData({ statusHeight, rightSpace: valid ? menuRightSpace(info.windowWidth, menu.left) : 104 })
          } catch { /* 取不到系统几何时保持默认值。 */ }
          // 会话失效或退出时立即清除旧名片；昵称头像仍以服务端资料为准。
          if (!this._disposeSession) this._disposeSession = api.onSessionChange(() => {
            this.setData(api.loggedIn ? { profile: profileView(api.me || {}), loggedIn: true } : guestProfile())
            if (!api.loggedIn) this.getTabBar()?.setData({ unreadCount: 0 })
          })
          return this.reload()
        },
        async reload() {
          if (!api.loggedIn) { this.setData(guestProfile()); return }
          const userId = api.me?.id
          try { const summary = await service.profileSummary(); if (api.loggedIn && api.me?.id === userId) this.setData({ ...summary, loggedIn: true, loadFailed: false }) }
          catch (error) { this.setData(api.loggedIn ? { loadFailed: true, loggedIn: true } : guestProfile()); toast(error) }
          if (api.loggedIn && api.me?.id === userId && userId) {
            try { const posts = (await service.all('/users/' + encodeURIComponent(userId) + '/posts')).map(postView); if (api.loggedIn && api.me?.id === userId) this.setData({ posts, postsFailed: false }) }
            catch { if (api.loggedIn && api.me?.id === userId) this.setData({ posts: [], postsFailed: true }) }
            try { const liked = (await service.likes()).posts; if (api.loggedIn && api.me?.id === userId) this.setData({ likedPosts: liked, likedFailed: false }) }
            catch { if (api.loggedIn && api.me?.id === userId) this.setData({ likedPosts: [], likedFailed: true }) }
          }
        },
        onTabTap(event: Json) { const tab = event.currentTarget.dataset.tab; if (tab === 'posts' || tab === 'liked') this.setData({ activeTab: tab }) },
        onBack() { wx.navigateBack({ delta: 1, fail() { wx.switchTab({ url: '/pages/forum/index' }) } }) },
        onMoreTap() {
          wx.showActionSheet({ itemList: ['编辑个人资料', '我的帖子', '我的点赞', '意见反馈', '关于信科 Link'], success: (result: Json) => {
            if (result.tapIndex === 0) this.onEditTap()
            if (result.tapIndex === 1) this.onMyPostsTap()
            if (result.tapIndex === 2) this.onCollectionsTap()
            if (result.tapIndex === 3) this.onFeedbackTap()
            if (result.tapIndex === 4) this.onAboutTap()
          }, fail: () => { /* 取消不提示 */ } })
        },
        onShareAppMessage() { return { title: (this.data.profile?.nickname || '我') + '的主页 · 信科 Link', path: api.me?.id ? '/pages/user-home/index?id=' + encodeURIComponent(api.me.id) : '/pages/forum/index' } },
        onHomeTap() { if (!api.loggedIn) return this.onLoginTap(); if (api.me) navigate('user-home', api.me.id); return Promise.resolve() },
        onMyPostsTap() { return openProtected(this, '/pages/my-posts/index') },
        saveInteraction(id: string, field: string) { return interaction(this, id, field, [...(this.data.posts || []), ...(this.data.likedPosts || [])]) },
        onCollectionsTap() { return openProtected(this, '/pages/my-collections/index') },
        onEditTap() { return openProtected(this, '/pages/profile-edit/index') },
        onFeedbackTap() { return openProtected(this, '/pages/feedback/index') },
        onAboutTap() { wx.showModal({ title: '关于信科 Link', content: '连接校园里的每一种生活。专业、班级和入学年份由用户自填，未经平台核实。学号仅保存在本机。', showCancel: false }) },
      },
      'profile-edit': {
        data: { form: profileView({ nickname: '' }) },
        onLoad() { return this.reload() },
        async reload() { try { this.setData({ form: await service.profile(), loadFailed: false }); this._detail = api.me } catch (error) { this.setData({ loadFailed: true }); toast(error) } },
        onChooseAvatar(event: Json) { if (!this.data.saving && event.detail.avatarUrl) this.setData({ form: { ...this.data.form, avatar: event.detail.avatarUrl } }) },
        onNicknameBlur(event: Json) { if (!this.data.saving) this.setData({ form: { ...this.data.form, nickname: event.detail.value } }) },
        async onAvatarTap() {
          if (!await api.requireSession() || this.data.saving || this.data.choosing) return
          this.setData({ choosing: true }); wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], success: result => {
            const file = result.tempFiles[0]; if (file && file.size <= 10 * 1024 * 1024) this.setData({ form: { ...this.data.form, avatar: file.tempFilePath } }); else toast(new Error('请选择10MB以内的图片'))
          }, complete: () => this.setData({ choosing: false }) })
        },
        async onSaveTap() {
          if (!await api.requireSession() || this.data.loadFailed) return Promise.resolve()
          return safe(this, async () => {
            const form = this.data.form
            if (!form.nickname.trim() || form.nickname.length > 20 || form.bio.length > 40 || form.major.length > 60 || form.className.length > 40 || form.studentId.length > 32 || form.year && !/^(19|20)\d{2}$/.test(form.year)) throw new Error('请检查资料格式')
            let avatarImageId = this._detail.avatar?.imageId || null
            if (form.avatar && form.avatar !== profileView(this._detail).avatar && backendImageId(form.avatar) !== String(avatarImageId)) {
              avatarImageId = (await api.upload(form.avatar, 'avatar')).id
            }
            const me = await api.request('/me', 'PATCH', { nickname: form.nickname.trim(), bio: form.bio.trim(), major: form.major.trim(), className: form.className.trim(), enrollmentYear: form.year || null, avatarImageId, expectedVersion: this._detail.version })
            api.setMe(me); wx.setStorageSync(LOCAL_STUDENT_ID_KEY, form.studentId.trim()); this._detail = me
            wx.showToast({ title: '资料已保存', icon: 'success' }); wx.navigateBack({ delta: 1 })
          }, 'saving')
        },
      },
      'user-home': {
        async onLoad(options: Json = {}) { this._id = options.id || api.me?.id || ''; this.readLayout?.(); await this.reload() },
        async reload() { try { if (!this._id) throw new Error('用户不存在'); this.setData({ profile: await service.publicProfile(this._id), loggedIn: api.loggedIn, loadFailed: false }) } catch (error) { this.setData({ profile: null, loadFailed: true }); toast(error) } },
        async onMessageTap() { if (this.data.profile && !this.data.profile.isSelf && await api.requireSession()) wx.navigateTo({ url: '/pages/contact-chat/index?peerId=' + encodeURIComponent(this._id) }) },
        saveInteraction(id: string, field: string) { return interaction(this, id, field, this.data.profile?.posts || []) },
      },
      'my-posts': {
        onPublishTap() { return openProtected(this, '/pages/forum-publish/index') },
        async reload() { const userId = api.me?.id; try { const posts = (await service.all('/me/posts')).map(postView); if (!api.loggedIn || api.me?.id !== userId || this._unloaded) return; this.setData({ posts, loggedIn: true, loadFailed: false }) } catch (error) { this.setData({ loadFailed: true }); toast(error) } },
        saveInteraction(id: string, field: string) { return interaction(this, id, field, this.data.posts) },
        async onDeleteTap(event: Json) {
          if (!await api.requireSession()) return Promise.resolve()
          const id = event.currentTarget.dataset.id, target = this.data.posts.find((post: Json) => post.id === id), userId = api.me?.id
          return safe(this, async () => { if (!target || !await confirm('删除这条帖子？', '删除后无法恢复。') || !api.loggedIn || api.me?.id !== userId) return; await api.request('/posts/' + id, 'DELETE', undefined, { 'If-Match': '"' + target.version + '"' }); await this.reload() }, 'deleting')
        },
        onEditTap(event: Json) { const id = event.currentTarget.dataset.id; wx.navigateTo({ url: '/pages/forum-publish/index?editId=' + encodeURIComponent(id) }) },
      },
      'my-collections': {
        async reload() { const userId = api.me?.id; try { const liked = await service.likes(); if (!api.loggedIn || api.me?.id !== userId || this._unloaded) return; this.setData({ ...liked, loggedIn: true, loadFailed: false }) } catch (error) { this.setData({ loadFailed: true }); toast(error) } },
        saveInteraction(id: string, field: string) { return interaction(this, id, field, this.data.posts) },
      },
      feedback: {},
      contact: {
        async onShow() { this.getTabBar()?.setData({ activeKey: name === 'notifications' ? 'notifications' : 'contact', publishVisible: false }); if (!this._realtime) this._realtime = new Realtime(api, () => { void this.reload() }); this._realtime.start(); await this.reload() },
        onHide() { this._realtime?.stop() }, onUnload() { this._realtime?.stop() },
        async reload() {
          if (getViewerRole(wx) === 'guest') { this.setData({ guest: true, conversations: [], visibleConversations: [], notifications: [], visibleNotifications: [], messageUnread: 0, notificationUnread: 0 }); return }
          const userId = api.me?.id
          const outcomes = await Promise.all([service.conversations(), service.notifications(), api.request('/me/unread')].map(task => task.then(value => ({ status: 'fulfilled', value }), error => ({ status: 'rejected', value: null, error }))))
          if (!api.loggedIn || api.me?.id !== userId) return
          const patch: Json = { guest: false }
          if (outcomes[0].status === 'fulfilled') { patch.conversations = outcomes[0].value; patch.messagesFailed = false } else patch.messagesFailed = true
          if (outcomes[1].status === 'fulfilled') { patch.notifications = outcomes[1].value; patch.notificationsFailed = false } else patch.notificationsFailed = true
          if (outcomes[2].status === 'fulfilled') { const unread = outcomes[2].value; patch.messageUnread = unread.raw.messages; patch.notificationUnread = unread.raw.comments + unread.raw.reactions + unread.raw.announcements; this.getTabBar()?.setData({ unreadCount: unread.badgeTotal, messageUnread: unread.raw.messages, notificationUnread: unread.raw.comments + unread.raw.reactions + unread.raw.announcements }) }
          this.setData(patch); this.updateVisibleNotifications(); this.setData({ loadFailed: this.data.activeTab === 'messages' ? this.data.messagesFailed : this.data.notificationsFailed })
        },
        refreshInbox() { return this.reload() }, updateTabBadge() {},
        onConversationTap(event: Json) { wx.navigateTo({ url: '/pages/contact-chat/index?conversationId=' + encodeURIComponent(event.currentTarget.dataset.id) }) },
        onNotificationTap(event: Json) {
          const notice = this.data.notifications.find((item: Json) => item.id === event.currentTarget.dataset.id); if (!notice) return Promise.resolve()
          return safe(this, async () => {
            await api.request('/notifications/' + notice.id + '/read', 'PUT'); await this.reload()
            if (notice.targetKind === 'announcement') { let body = notice.detail || notice.quote; if (notice.targetId && notice.availability === 'available') body = (await api.request('/announcements/' + notice.targetId)).body; wx.showModal({ title: notice.summary, content: body, showCancel: false }); return }
            if (notice.availability !== 'available') { toast(new Error('内容已不可用')); return }; navigate('forum-detail', notice.targetId)
          })
        },
        onMarkAllRead() { return safe(this, async () => { await api.request('/me/' + (this.data.activeTab === 'messages' ? 'conversations' : 'notifications') + '/read', 'POST', {}); await this.reload() }, 'busy') },
      },
      report: {
        async onLoad(options: Json = {}) {
          const kind = ['post', 'comment', 'user'].includes(options.kind) ? options.kind : 'post', id = options.id || ''
          this.setData({ targetKind: kind, targetId: id, targetMissing: !id })
          try { if (kind === 'comment') { this.setData({ targetTitle: '举报评论' }); return }; const target = await api.request('/' + (kind === 'post' ? 'posts' : 'users') + '/' + encodeURIComponent(id)); this.setData({ targetTitle: target.content?.slice(0, 40) || target.title || target.nickname || '举报对象', targetMissing: false }) } catch (error) { this.setData({ targetMissing: true }); toast(error) }
        },
        onShow() {},
        async onSubmitReport() {
          if (!await api.requireSession() || this.data.targetMissing) return Promise.resolve()
          const userId = api.me?.id
          return safe(this, async () => {
            const codes = ['commercial_ad', 'fraud_or_offsite_payment', 'harassment', 'impersonation', 'other'], index = this.data.reasons.indexOf(this.data.reason)
            if (index < 0 || !this.data.detail.trim() || this.data.detail.length > 200) throw new Error('请选择原因并填写1至200字说明')
            if (!await confirm('确认提交举报？', '请确认举报内容真实、准确。') || !api.loggedIn || api.me?.id !== userId) return
            const evidenceImageIds = await service.images(this.data.evidence, 'report')
            if (!api.loggedIn || api.me?.id !== userId) return
            const body = { targetType: this.data.targetKind, targetId: this.data.targetId, reasonCode: codes[index], description: this.data.detail.trim(), evidenceImageIds }
            const signature = JSON.stringify(body); if (this._reportSignature !== signature) { this._reportSignature = signature; this._reportRequestId = requestId() }
            await api.request('/reports', 'POST', { ...body, clientRequestId: this._reportRequestId })
            wx.showModal({ title: '举报已提交', content: '平台已收到举报，将按规则处理。', showCancel: false, success: () => wx.navigateBack({ delta: 1 }) })
          }, 'submitting')
        },
      },
    }
    // Chat and settings share one server conversation ID; peer IDs never substitute for it.
    const chat: Json = {
      _conversationId: '', _peerId: '', _loading: null, _stopped: false,
      async onLoad(options: Json = {}) { this._conversationId = options.conversationId || ''; this._peerId = options.peerId || ''; this._targetMessageId = options.messageId || ''; this._messages = []; await this.reload() },
      async onShow() { this._stopped = false; if (!this._realtime) this._realtime = new Realtime(api, () => { void this.reload() }); this._realtime.start(); await this.reload() },
      onHide() { this._stopped = true; this._realtime?.stop() }, onUnload() { this._stopped = true; this._realtime?.stop() },
      async reload() {
        if (this._loading) return this._loading
        this._loading = (async () => {
          try {
            if (!this._conversationId) { if (!this._peerId) throw new Error('会话不存在'); this._conversationId = (await service.conversation(this._peerId)).id }
            const conversation = await api.request('/conversations/' + this._conversationId); this._peerId = conversation.peer.id
            const settings = await api.request('/conversations/' + this._conversationId + '/settings'); this._settings = settings
            // Keep the existing DTO projection; the raw request ID is used only to
            // reconcile this page's transient bubble with an authoritative record.
            let acknowledgedRequestId = ''
            const messagesPage = async (args: Json = {}): Promise<Json> => {
              const response = await api.request('/conversations/' + encodeURIComponent(this._conversationId) + '/messages' + query({ size: 100, ...args }))
              if (this._sendRequestId && response.items.some((item: Json) => item.isMine && item.clientMessageId === this._sendRequestId)) acknowledgedRequestId = this._sendRequestId
              return { ...response, items: response.items.map(messageView) }
            }
            let page = await messagesPage(this._targetMessageId ? { aroundMessageId: this._targetMessageId } : {})
            let messages = page.items
            // On reconnection HTTP refetches known history too, so hidden placeholders replace stale text.
            let before = page.nextBeforeSequence
            const known = this._messages?.[0]?.sequence
            while (page.hasMore && before && known && messages[0] && (messages[0].sequence.length > known.length || messages[0].sequence.length === known.length && messages[0].sequence > known)) {
              page = await messagesPage({ beforeSequence: before }); messages = mergeMessages(messages, page.items); before = page.nextBeforeSequence
            }
            // Replace the authoritative window, including cleared/hidden records; retain only older loaded pages.
            const first = messages[0]?.sequence
            const older = page.hasMore && first ? (this._messages || []).filter((item: Json) => item.sequence.length < first.length || item.sequence.length === first.length && item.sequence < first) : []
            this._messages = mergeMessages(older, messages); this._before = before; this._hasOlder = page.hasMore
            const views = this._messages.map((message: Json, index: number) => ({ ...message, showTime: index === 0 || message.createdAt - this._messages[index - 1].createdAt >= 300000, timeText: chatTimeLabel(message.createdAt) }))
            let readStateFailed = false
            if (views.length) { try { await api.request('/conversations/' + this._conversationId + '/read', 'PUT', { readUpToSequence: views[views.length - 1].sequence }) } catch { readStateFailed = true } }
            // 头像地址有有效期，不能一直沿用登录时缓存；刷新资料不触发会话重连。
            const self = profileView(await api.request('/me').catch(() => ({ ...api.me, avatar: null })))
            const acknowledgement: Json = {}
            if (acknowledgedRequestId && acknowledgedRequestId === this._sendRequestId) {
              acknowledgement.sendAcknowledged = true
              acknowledgement.failedText = ''
              if (this.data.input.trim() === this._sendText) acknowledgement.input = ''
            }
            this.setData({ peer: service.conversationView(conversation).peer, messages: views, lastSelfMessageId: views.filter((message: Json) => message.fromSelf).slice(-1)[0]?.id || '', ownBlocked: settings.blocked === true, selfAvatar: self.avatar, selfNickname: self.nickname || '我',
              ...acknowledgement,
              scrollTo: 'message-' + (this._targetMessageId || views[views.length - 1]?.id || ''), loadFailed: false, missing: false, readStateFailed,
              canSend: !settings.blocked, blockedReason: settings.blocked === true ? '你已屏蔽这位同学，解除后才能发送消息' : '' })
          } catch (error) { this.setData({ loadFailed: true, canSend: false }); toast(error) }
        })()
        try { await this._loading } finally { this._loading = null }
      },
      refreshConversation() { return this.reload() },
      onChatAvatarError(event: Json) { return this.onRemoteAvatarError({ currentTarget: { dataset: { ...event.currentTarget.dataset, kind: event.currentTarget.dataset.owner } } }) },
      async onScrollToUpper() {
        if (!this._hasOlder || !this._before) return
        try { const page = await service.messages(this._conversationId, { beforeSequence: this._before }); this._messages = mergeMessages(this._messages, page.items); this._before = page.nextBeforeSequence; this._hasOlder = page.hasMore; await this.reload() } catch (error) { toast(error) }
      },
      async onSendTap() {
        if (!await api.requireSession() || !this._conversationId || !this.data.canSend) return Promise.resolve()
        return safe(this, async () => {
          const input = this.data.input, text = input.trim(); if (!text || text.length > 1000) throw new Error('消息需为1至1000字')
          if (this._sendText !== text || this.data.sendAcknowledged) { this._sendText = text; this._sendRequestId = requestId() }
          this.setData({ sendAcknowledged: false })
          try { await service.send(this._conversationId, text, this._sendRequestId) } catch (error) { if (this.data.sendAcknowledged) return; this.setData({ failedText: input }); throw error }
          this._sendText = ''; this.setData({ ...(this.data.input.trim() === text ? { input: '' } : {}), failedText: '' }); await this.reload()
        }, 'sending')
      },
      onRetrySendTap() { if (this.data.sending || !this.data.failedText) return; this.setData({ input: this.data.failedText }); return this.onSendTap() },
      onSettingsTap() { wx.navigateTo({ url: '/pages/chat-settings/index?conversationId=' + encodeURIComponent(this._conversationId) }) },
    }
    definitions['contact-chat'] = chat
    definitions['chat-settings'] = {
      _conversationId: '',
      async onLoad(options: Json = {}) { this._conversationId = options.conversationId || ''; this._peerId = options.peerId || ''; await this.reload() },
      async reload() {
        const userId = api.me?.id
        try {
          if (!this._conversationId) { if (!this._peerId) throw new Error('会话不存在'); this._conversationId = (await service.conversation(this._peerId)).id }
          const [conversation, preferences] = await Promise.all([api.request('/conversations/' + this._conversationId), api.request('/conversations/' + this._conversationId + '/settings')])
          if (!api.loggedIn || api.me?.id !== userId || this._unloaded) return
          this._peerId = conversation.peer.id
          this.setData({ peer: service.conversationView(conversation).peer, preferences, loggedIn: true, loadFailed: false })
        } catch (error) { this.setData({ loadFailed: true }); toast(error) }
      },
      async onPreferenceChange(event: Json) {
        const key = event.currentTarget.dataset.key, value = event.detail.value
        if (!['pinned', 'muted', 'blocked'].includes(key) || !await api.requireSession()) return Promise.resolve()
        const userId = api.me?.id
        return safe(this, async () => {
          if (key === 'blocked') { if (value && !await confirm('屏蔽这位同学？', '屏蔽后本人无法继续向对方发送消息。')) return; if (!api.loggedIn || api.me?.id !== userId) return; await api.request('/me/blocks/' + this._peerId, value ? 'PUT' : 'DELETE') }
          else await api.request('/conversations/' + this._conversationId + '/settings', 'PATCH', { [key]: value, expectedVersion: this.data.preferences.version })
          await this.reload()
        }, 'busy')
      },
      async onHistoryInput(event: Json) {
        const historyQuery = event.detail.value.slice(0, 60); this.setData({ historyQuery }); const generation = ++this._requestGeneration, userId = api.me?.id
        try { const response = historyQuery.trim() ? await service.messages(this._conversationId, { q: historyQuery.trim() }) : { items: [] }; if (generation === this._requestGeneration && api.loggedIn && api.me?.id === userId && !this._unloaded) this.setData({ historyMatches: response.items }) } catch (error) { toast(error) }
      },
      onFindHistoryTap() { this.setData({ historyOpen: !this.data.historyOpen, historyQuery: '', historyMatches: [] }) },
      onReportTap() { if (this.data.peer) wx.navigateTo({ url: '/pages/report/index?kind=user&id=' + encodeURIComponent(this.data.peer.id) }) },
      onHistoryMessageTap(event: Json) { if (!this.data.historyMatches.some((item: Json) => item.id === event.currentTarget.dataset.id)) return; wx.navigateTo({ url: '/pages/contact-chat/index?conversationId=' + encodeURIComponent(this._conversationId) + '&messageId=' + encodeURIComponent(event.currentTarget.dataset.id) }) },
      onClearHistoryTap() {
        const userId = api.me?.id
        return safe(this, async () => { if (!await confirm('清空聊天记录？', '仅清空本人可见的记录，不影响对方。') || !api.loggedIn || api.me?.id !== userId) return; const page = await service.messages(this._conversationId); const sequence = page.items[page.items.length - 1]?.sequence; if (sequence && api.loggedIn && api.me?.id === userId) await api.request('/conversations/' + this._conversationId + '/clear', 'POST', { clearThroughSequence: sequence, confirmed: true }); this.setData({ historyMatches: [] }); await this.reload() }, 'busy')
      },
    }
    definitions.notifications = definitions.contact
    definitions.ai = { onShow: fixture.onShow, onUnload: fixture.onUnload, reload() {} }
    definitions['new-chat'] = {
      async reload() { if (!api.loggedIn) { this.setData({ peers: [], loadFailed: false }); return }; try { const conversations = await service.conversations(); this.setData({ peers: conversations.map(item => item.peer), loadFailed: false }); this.filterPeers?.() } catch (error) { this.setData({ peers: [], loadFailed: true }); toast(error) } },
      async onStartChat(event: Json) { const id = event.currentTarget?.dataset?.peerId || event.currentTarget?.dataset?.id || event.detail?.peerId; if (typeof id !== 'string' || !id || id === api.me?.id) return; if (await api.requireSession()) wx.navigateTo({ url: '/pages/contact-chat/index?peerId=' + encodeURIComponent(id) }) },
    }
    definitions.drafts = { onShow: fixture.onShow, reload() {}, onOpenPost() { return safe(this, async () => { if (await api.requireSession()) wx.navigateTo({ url: '/pages/forum-publish/index?draft=1' }) }) } }
    const implementation = definitions[name]
    if (!implementation) throw new Error('Missing real backend page: ' + name)
    const definition: Json = { ...fixture, ...common, ...implementation, data: { ...fixture.data, ...pageData, ...implementation.data } }
    // 约定式契约的兜底：feedback / report / forum-publish 等页面没有 reload，
    // 但 common 的 onShow、下拉刷新、重试按钮都会调用它，缺一个就在真机抛 TypeError。
    if (typeof definition.reload !== 'function') definition.reload = function () {}
    // 每个真实页面共用同一套原地资料确认流程，不跳转独立登录页。
    for (const lifecycle of ['onLoad', 'onShow']) {
      const original = definition[lifecycle]
      definition[lifecycle] = async function(this: any, ...args: any[]) {
        api.setProfilePrompt(() => this.requestProfileConsent())
        if (lifecycle === 'onShow') this._pageVisible = true
        await original?.apply(this, args)
        if (lifecycle === 'onShow' && this._pageVisible) {
          this._imageRenewal ||= new PageImageRenewal(runtime, this)
          await this._imageRenewal.start()
        }
      }
    }
    const originalHide = definition.onHide
    definition.onHide = function(this: any, ...args: any[]) {
      this._pageVisible = false; this._imageRenewal?.stop()
      return originalHide?.apply(this, args)
    }
    const originalUnload = definition.onUnload
    definition.onUnload = function(this: any, ...args: any[]) {
      this._unloaded = true; this._pageVisible = false; this._imageRenewal?.stop(); this._requestGeneration++
      try { if (originalUnload !== common.onUnload) originalUnload?.apply(this, args) }
      finally { common.onUnload.call(this) }
    }
    if (['my-posts', 'my-collections', 'profile-edit', 'contact', 'notifications', 'contact-chat', 'chat-settings'].includes(name)) {
      const reload = definition.reload
      definition.reload = async function(this: any, ...args: any[]): Promise<void> {
        if (getViewerRole(wx) === 'guest') { this.setData({ loggedIn: false, guest: true, posts: [], peers: [], conversations: [], visibleConversations: [], notifications: [], visibleNotifications: [], historyMatches: [], historyQuery: '', peer: null, preferences: {}, messages: [], loadFailed: false }); try { if (!await api.requireSession()) { this.setData({ loadFailed: true }); return } } catch (error) { this.setData({ loadFailed: true }); toast(error); return } }
        await reload.apply(this, args)
      }
    }
    // 历史查询、标读等入口同样先原地登录，不先发送匿名写请求。
    const protectedActions: Record<string, string[]> = {
      contact: ['onMarkAllRead', 'onNotificationTap'],
      'contact-chat': ['onScrollToUpper'],
      'chat-settings': ['onHistoryInput', 'onClearHistoryTap', 'onFindHistoryTap', 'onHistoryMessageTap', 'onReportTap'], 'my-posts': ['onEditTap'],
      'user-home': ['onEditTap'],
    }
    for (const actionName of protectedActions[name] || []) {
      const action = definition[actionName]
      definition[actionName] = async function(this: any, ...args: any[]): Promise<void> {
        if (await api.requireSession()) await action.apply(this, args)
      }
    }
    nativePage(definition)
  }) as typeof Page
}
