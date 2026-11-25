import {registerPage} from '../../backend/pages'
import {client,Runtime} from '../../backend/client'
import {postView} from '../../backend/adapters'
import {readForum,togglePostInteraction} from '../../features/forum/repository'
import {queryFeed} from '../../features/forum/feed'
import {FORUM_CATEGORIES,ForumPostView} from '../../features/forum/types'
import {addSearchHistory,clearSearchHistory,readSearchHistory,removeSearchHistory,SearchHistoryItem} from '../../features/search/history'
import {beijingWindow,fixtureRanking} from '../../features/statistics/views'
import {getViewerRole} from '../../features/profile/viewer'

export interface HotItem { id: string; rank: number; excerpt: string; categoryLabel: string; meta: string }

function excerpt(content: string): string {
  const line = content.replace(/\s+/g, ' ').trim()
  return line.length > 60 ? line.slice(0, 60) + '…' : line
}
function categoryLabel(category: string): string {
  return FORUM_CATEGORIES.find(item => item.key === category)?.label ?? '校园动态'
}

registerPage(wx,Page,'search')({
 data:{
   query:'',activeType:'posts',history:[] as SearchHistoryItem[],
   postResults:[] as ForumPostView[],hasSearched:false,searching:false,searchFailed:false,inputFocused:false,postHasMore:false,postPageFailed:false,loadingType:'',
   hot:[] as HotItem[],hotFailed:false,hotLoading:false,hotAvailable:false,hotUpdatedAt:'',hotWindow:'',
 },
 _hotGeneration:0,
 onLoad(options:Record<string,string|undefined>){if(options.type)this.setData({activeType:options.type})},
 onShow(){this.getTabBar()?.setData({activeKey:'search'});this.setData({history:readSearchHistory(wx)});if(this.data.hasSearched)this.runSearch();this.readHot()},
 // 演示模式一次返回全部结果（postHasMore 恒 false），wxml 的「显示更多」仍绑定了这个方法。
 onLoadMoreTap(){},
 onUnload(){this._hotGeneration++},
 async readHot(){
   const generation=++this._hotGeneration
   const fixtureMode=(wx as Runtime).__fixtureMode===true
   const owner=()=>getViewerRole(wx)==='guest'?'guest':String(client(wx).me?.id)
   const viewer=owner()
   const current=()=>generation===this._hotGeneration&&fixtureMode===((wx as Runtime).__fixtureMode===true)&&viewer===owner()
   this.setData({hotLoading:true,hotFailed:false})
   try{
     let ranked:{post:ForumPostView;pv:number;uv:number}[]
     let updatedAt:string|null,available:boolean,window:{startDate?:string;endDate?:string}
     if(fixtureMode){
       const snapshot=readForum(wx)
       const views=queryFeed(snapshot.posts,{category:'recommended',offset:0,limit:snapshot.posts.length,keyword:''},snapshot.interactions).items
       ranked=fixtureRanking(wx,views)
       updatedAt=new Date().toISOString();available=true;window=beijingWindow()
     }else{
       const response=await client(wx).request('/posts/view-ranking')
       if(!Array.isArray(response.items)||typeof response.available!=='boolean')throw new Error('榜单响应有误')
       ranked=response.items.map((item:{post:Record<string,any>;pv:number;uv:number})=>({post:postView(item.post),pv:item.pv,uv:item.uv}))
       updatedAt=response.updatedAt;available=response.available;window=response.window||{}
     }
     if(!current())return
     this.setData({hot:ranked.map(({post,pv,uv},index)=>({id:post.id,rank:index+1,excerpt:excerpt(post.content),categoryLabel:categoryLabel(post.category),meta:uv+' 位访客 · '+pv+' 次浏览'})),hotFailed:false,hotAvailable:available,hotUpdatedAt:updatedAt||'',hotWindow:window.startDate&&window.endDate?window.startDate+' 至 '+window.endDate:''})
   }catch{if(current())this.setData({hot:[],hotFailed:true,hotAvailable:false,hotUpdatedAt:''})}
   finally{if(current())this.setData({hotLoading:false})}
 },
 onHotTap(e:WechatMiniprogram.TouchEvent){const id=e.currentTarget.dataset.id;if(!id)return;wx.navigateTo({url:'/pages/forum-detail/index?id='+encodeURIComponent(String(id)),fail(){wx.showToast({title:'详情暂时无法打开',icon:'none'})}})},
 onInput(e:WechatMiniprogram.CustomEvent<{value:string}>){this.setData({query:e.detail.value.slice(0,60),hasSearched:false,postResults:[],searchFailed:false})},onFocus(){this.setData({inputFocused:true})},onBlur(){this.setData({inputFocused:false})},
 onSubmit(){const q=this.data.query.trim();if(!q)return;this.setData({query:q,inputFocused:false});try{this.setData({history:addSearchHistory(wx,q,'posts')})}catch{wx.showToast({title:'搜索记录未保存，请重试',icon:'none'})}wx.hideKeyboard?.();this.runSearch(q)},
 runSearch(value?:string){const q=value??this.data.query;try{const s=readForum(wx),response=queryFeed(s.posts,{category:'recommended',offset:0,limit:100,keyword:q},s.interactions);this.setData({postResults:response.items,hasSearched:true,searchFailed:false})}catch{this.setData({searchFailed:true})}},
 onClearInput(){this.setData({query:'',hasSearched:false,inputFocused:true,postResults:[]})},onCancelTap(){this.onClearInput()},
 onHistoryTap(e:WechatMiniprogram.TouchEvent){const item=this.data.history[Number(e.currentTarget.dataset.index)];if(item){this.setData({query:item.query});this.onSubmit()}},
 onRemoveHistory(e:WechatMiniprogram.TouchEvent){const item=this.data.history[Number(e.currentTarget.dataset.index)];if(!item)return;try{this.setData({history:removeSearchHistory(wx,item.query,item.type)})}catch{wx.showToast({title:'搜索记录未删除，请重试',icon:'none'})}},
 onClearHistory(){try{clearSearchHistory(wx);this.setData({history:[]})}catch{wx.showToast({title:'搜索记录未清空，请重试',icon:'none'})}},
 onOpenPost(e:WechatMiniprogram.CustomEvent<{id:string}>){wx.navigateTo({url:'/pages/forum-detail/index?id='+encodeURIComponent(e.detail.id)})},
 savePostInteraction(id:string,field:'liked'|'collected'|'reposted'){if(!this.data.hasSearched||this.data.searchFailed)return;if(!this.data.postResults.some(post=>post.id===id))return;try{togglePostInteraction(wx,id,field);this.runSearch()}catch(error){wx.showToast({title:error instanceof Error?error.message:'互动未完成，请重试',icon:'none'})}},
 onLikePost(e:WechatMiniprogram.CustomEvent<{id:string}>){this.savePostInteraction(e.detail.id,'liked')},
 onRepostPost() { wx.showToast({ title: '转发功能尚未开放', icon: 'none' }) },
 onCollectPost(e:WechatMiniprogram.CustomEvent<{id:string}>){this.savePostInteraction(e.detail.id,'collected')},
 onShareAppMessage(e:WechatMiniprogram.Page.IShareAppMessageOption){const post=this.data.postResults.find(p=>p.id===e.target?.dataset.postId);return {title:post?.content.slice(0,50)||'信科 Link',path:post?'/pages/forum-detail/index?id='+encodeURIComponent(post.id):'/pages/forum/index'}},
})
