import {LocalStorage,readProfile,UserProfile} from './repository'
import {readForum,LOCAL_AUTHOR_ID} from '../forum/repository'
import {toPostView} from '../forum/feed'
import {ForumPostView} from '../forum/types'
import {resolvePeer} from '../contact/repository'
export function getMyPosts(storage:LocalStorage):ForumPostView[] {const s=readForum(storage);return s.posts.filter(p=>p.author.id===LOCAL_AUTHOR_ID).sort((a,b)=>b.createdAt-a.createdAt).map(p=>toPostView(p,s.interactions))}
/** 公开主页的帖子：匿名帖子不进入公开视图，只由「我的帖子」管理。 */
export function getPublicPosts(storage:LocalStorage):ForumPostView[] {return getMyPosts(storage).filter(p=>!p.isAnonymous)}
/** 个人主页「喜欢」分栏：我点过赞的帖子，按发布时间倒序。 */
export function getLikedPosts(storage:LocalStorage):ForumPostView[] {const s=readForum(storage);return s.posts.map(p=>toPostView(p,s.interactions)).filter(p=>p.liked)}
export function getCollections(storage:LocalStorage):{posts:ForumPostView[]} {const s=readForum(storage);return {posts:s.posts.map(p=>toPostView(p,s.interactions)).filter(p=>p.collected)}}
export function getProfileSummary(storage:LocalStorage) {const profile=readProfile(storage),posts=getMyPosts(storage);return {profile,postCount:posts.length,likeCount:posts.reduce((n,p)=>n+p.likeCount,0),collectionCount:getCollections(storage).posts.length}}
export interface PublicProfile extends Omit<UserProfile,'version'|'studentId'> {id:string;isSelf:boolean;posts:ForumPostView[];likeCount:number}
export function getPublicProfile(storage:LocalStorage,id:string):PublicProfile {
 const s=readForum(storage),own=id===LOCAL_AUTHOR_ID,peer=own?undefined:resolvePeer(storage,id),author=s.posts.find(p=>p.author.id===id&&!p.anonymousNumber)?.author
 if(!own&&!peer&&!author)throw new Error('用户不存在')
 const p=own?readProfile(storage):undefined
 const identity=p?{nickname:p.nickname,avatar:p.avatar,bio:p.bio,major:p.major,className:p.className,year:p.year}:{nickname:peer?.nickname??author!.nickname,avatar:peer?.avatar??author!.avatar,bio:peer?.bio??'',major:'',className:'',year:''}
 const posts=s.posts.filter(p=>p.author.id===id&&!p.anonymousNumber).map(p=>toPostView(p,s.interactions))
 return {...identity,id,isSelf:own,posts,likeCount:posts.reduce((n,p)=>n+p.likeCount,0)}
}
