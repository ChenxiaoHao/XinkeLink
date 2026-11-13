import {registerPage} from '../../backend/pages'
import {client,Runtime} from '../../backend/client'
import {postView} from '../../backend/adapters'
import {readForum,togglePostInteraction} from '../../features/forum/repository'
import {queryFeed} from '../../features/forum/feed'
import {FORUM_CATEGORIES,ForumPostView} from '../../features/forum/types'
import {addSearchHistory,clearSearchHistory,readSearchHistory,removeSearchHistory,SearchHistoryItem} from '../../features/search/history'

export interface HotItem { id: string; rank: number; excerpt: string; categoryLabel: string; meta: string }

const HOT_LIMIT = 5
const WEEK_MS = 7 * 24 * 60 * 60 * 1000

function excerpt(content: string): string {
  const line = content.replace(/\s+/g, ' ').trim()
  return line.length > 60 ? line.slice(0, 60) + '…' : line
}
function likeLabel(count: number): string {
  if (count >= 10000) return (count / 10000).toFixed(1) + ' 万'
  return String(count)
}
function categoryLabel(category: string): string {
  return FORUM_CATEGORIES.find(item => item.key === category)?.label ?? '校园动态'
}

registerPage(wx,Page,'search')({
 data:{
   query:'',activeType:'posts',history:[] as SearchHistoryItem[],
   postResults:[] as ForumPostView[],hasSearched:false,searching:false,searchFailed:false,inputFocused:false,postHasMore:false,postPageFailed:false,loadingType:'',
   hot:[] as HotItem[],hotFailed:false,
 },
 onLoad(options:Record<string,string|undefined>){this.setData({history:readSearchHistory(wx)});this.readHot();if(options.type)this.setData({activeType:options.type})},
 onShow(){this.getTabBar()?.setData({activeKey:'search'});this.readHot()},
 // 演示模式一次返回全部结果（postHasMore 恒 false），wxml 的「显示更多」仍绑定了这个方法。
 onLoadMoreTap(){},
 // 热榜 = 当周（最近 7 天）点赞最多的前 5 条。真正的按周聚合需要服务端支持，
 // 这里用帖子发布时间近似「当周」，当周不足 5 条时用全量补齐，保证榜单不空。
 async readHot(){
   try{
     let posts:ForumPostView[]
     let createdOf:(id:string)=>number
     if((wx as Runtime).__fixtureMode===true){
       const snapshot=readForum(wx)
       posts=queryFeed(snapshot.posts,{category:'recommended',offset:0,limit:100,keyword:''},snapshot.interactions).items
       const stamps=new Map(snapshot.posts.map(post=>[post.id,post.createdAt]))
       createdOf=(id)=>stamps.get(id)??0
     }else{
       const response=await client(wx).request('/posts?sort=recommended&size=50')
       posts=response.items.map(postView)
       const stamps=new Map<string,number>(response.items.map((item:{id:string;createdAt?:string})=>[item.id,Date.parse(String(item.createdAt))||0]))
       createdOf=(id)=>stamps.get(id)??0
     }
     const now=Date.now()
     const weekly=posts.filter(post=>createdOf(post.id)>=now-WEEK_MS)
     const fallback=weekly.length>=HOT_LIMIT?[]:posts.filter(post=>createdOf(post.id)<now-WEEK_MS)
     const ranked=[...weekly,...fallback].sort((a,b)=>b.likeCount-a.likeCount).slice(0,HOT_LIMIT)
     this.setData({hot:ranked.map((post,index)=>({id:post.id,rank:index+1,excerpt:excerpt(post.content),categoryLabel:categoryLabel(post.category),meta:likeLabel(post.likeCount)+' 赞'})),hotFailed:false})
   }catch{this.setData({hot:[],hotFailed:true})}
 },
 onHotTap(e:WechatMiniprogram.TouchEvent){const id=e.currentTarget.dataset.id;if(!id)return;wx.navigateTo({url:'/pages/forum-detail/index?id='+encodeURIComponent(String(id)),fail(){wx.showToast({title:'详情暂时无法打开',icon:'none'})}})},
 onInput(e:WechatMiniprogram.CustomEvent<{value:string}>){this.setData({query:e.detail.value.slice(0,60),hasSearched:false})},onFocus(){this.setData({inputFocused:true})},onBlur(){this.setData({inputFocused:false})},
 onSubmit(){const q=this.data.query.trim();if(!q)return;this.setData({query:q,history:addSearchHistory(wx,q,'posts'),inputFocused:false});wx.hideKeyboard?.();this.runSearch(q)},
 runSearch(value?:string){const q=value??this.data.query;try{const s=readForum(wx),response=queryFeed(s.posts,{category:'recommended',offset:0,limit:100,keyword:q},s.interactions);this.setData({postResults:response.items,hasSearched:true,searchFailed:false})}catch{this.setData({searchFailed:true})}},
 onClearInput(){this.setData({query:'',hasSearched:false,inputFocused:true,postResults:[]})},onCancelTap(){this.onClearInput()},
 onHistoryTap(e:WechatMiniprogram.TouchEvent){const item=this.data.history[Number(e.currentTarget.dataset.index)];if(item){this.setData({query:item.query});this.onSubmit()}},
 onRemoveHistory(e:WechatMiniprogram.TouchEvent){const item=this.data.history[Number(e.currentTarget.dataset.index)];if(!item)return;this.setData({history:removeSearchHistory(wx,item.query,item.type)})},
 onClearHistory(){clearSearchHistory(wx);this.setData({history:[]})},
 onOpenPost(e:WechatMiniprogram.CustomEvent<{id:string}>){wx.navigateTo({url:'/pages/forum-detail/index?id='+encodeURIComponent(e.detail.id)})},
 savePostInteraction(id:string,field:'liked'|'collected'|'reposted'){togglePostInteraction(wx,id,field);this.runSearch()},
 onLikePost(e:WechatMiniprogram.CustomEvent<{id:string}>){this.savePostInteraction(e.detail.id,'liked')},
 onRepostPost(e:WechatMiniprogram.CustomEvent<{id:string}>){this.savePostInteraction(e.detail.id,'reposted')},
 onCollectPost(e:WechatMiniprogram.CustomEvent<{id:string}>){this.savePostInteraction(e.detail.id,'collected')},
 onShareAppMessage(e:WechatMiniprogram.Page.IShareAppMessageOption){const post=this.data.postResults.find(p=>p.id===e.target?.dataset.postId);return {title:post?.content.slice(0,50)||'信科 Link',path:post?'/pages/forum-detail/index?id='+encodeURIComponent(post.id):'/pages/forum/index'}},
})
