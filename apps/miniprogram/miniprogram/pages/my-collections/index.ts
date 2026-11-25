import {requireLocalSession} from '../../features/profile/repository'
import { getViewerRole } from '../../features/profile/viewer'
import {readForum,togglePostInteraction} from '../../features/forum/repository'
import {toPostView} from '../../features/forum/feed'
import { registerPage } from '../../backend/pages'
import { ForumPostView, PostInteraction } from '../../features/forum/types'
registerPage(wx, Page, 'my-collections')({
 data: { posts: [] as ForumPostView[], loggedIn: false, loadFailed: false },
 onShow() { this.reload() },
 onShareAppMessage(event: WechatMiniprogram.Page.IShareAppMessageOption) { const post = this.data.posts.find(item => item.id === event.target?.dataset.postId); return { title: post?.content.slice(0,50) || '信科 Link · 校园动态', path: post ? '/pages/forum-detail/index?id=' + encodeURIComponent(post.id) : '/pages/forum/index' } },
 onOpenPost(event: WechatMiniprogram.CustomEvent<{id:string}>) { wx.navigateTo({ url:'/pages/forum-detail/index?id='+encodeURIComponent(event.detail.id) }) },
 // wxml 的重试 / 转发绑定原先只在真机侧有实现，演示模式下是死链。
 onRetryTap() { this.reload() },
 onLikePost(event: WechatMiniprogram.CustomEvent<{id:string}>) { this.saveInteraction(event.detail.id,'liked') },
 onRepostPost() { wx.showToast({ title: '转发功能尚未开放', icon: 'none' }) },
 onCollectPost(event: WechatMiniprogram.CustomEvent<{id:string}>) { this.saveInteraction(event.detail.id,'collected') },
 saveInteraction(id:string,field:keyof PostInteraction) {
  if(!requireLocalSession(wx))return
  try{togglePostInteraction(wx,id,field);this.reload()}
  catch{wx.showToast({title:'操作未保存，请重试',icon:'none'})}
 },
 reload(){
  if(getViewerRole(wx)==='guest'){this.setData({posts:[],loggedIn:false,loadFailed:false});return}
  try{
   const snapshot=readForum(wx)
   this.setData({posts:snapshot.posts.map(p=>toPostView(p,snapshot.interactions)).filter(p=>p.liked),loggedIn:true,loadFailed:false})
  }catch{this.setData({posts:[],loadFailed:true})}
 },
})
