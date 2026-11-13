const test=require('node:test'),assert=require('node:assert/strict');const {loadPage}=require('./page-harness.cjs');const {loadComponent}=require('./native-harness.cjs');
function runtime(){const saved=new Map(),calls={modals:[],toasts:[],routes:[]};const wx={getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v),showModal:o=>calls.modals.push(o),showToast:o=>calls.toasts.push(o.title),navigateTo:o=>calls.routes.push(o.url)};return{saved,calls,wx};}
test('visitor actions do not mutate forum state and point to local login',()=>{
 const r=runtime();r.saved.set('xinkelink.session.v1',false);const f=loadPage('pages/forum/index.js',r);f.onLoad({});f.onLikePost({detail:{id:'post-001'}});
 assert.equal(r.saved.size,1);assert.ok(r.calls.modals.length>=1);assert.equal(r.calls.routes.length,0);
 const d=loadPage('pages/forum-detail/index.js',r);d.onLoad({id:'post-001'});d.onCommentLike({detail:{id:d.data.comments[0].id}});assert.equal(r.saved.size,1);
});
test('post deletion removes collections and derived notifications and an old detail refreshes to missing',()=>{
 const r=runtime(),f=require('../.test-dist/features/forum/repository.js'),c=require('../.test-dist/features/profile/content.js'),n=require('../.test-dist/features/contact/notifications.js');
 const p=f.publishForumPost(r.wx,{content:'待删除的帖子',images:[],category:'help',anonymous:false},[]);f.togglePostInteraction(r.wx,p.id,'collected');const cid=f.addForumComment(r.wx,p.id,'一条评论');
 const page=loadPage('pages/forum-detail/index.js',r);page.onLoad({id:p.id});f.deleteOwnPost(r.wx,p.id);page.onShow();assert.equal(page.data.post,null);assert.equal(c.getCollections(r.wx).posts.some(x=>x.id===p.id),false);assert.equal(n.readNotifications(r.wx).some(x=>x.id==='activity-'+cid),false);assert.equal(page.onShareAppMessage().path,'/pages/forum/index');
});
test('contact badge follows preferences without removing messages or notices',()=>{
 const r=runtime(),p=require('../.test-dist/features/profile/repository.js'),badges=require('../.test-dist/features/contact/badges.js');const {instance}=loadComponent('custom-tab-bar/index.js',r);instance.refreshUnread();assert.equal(instance.data.unreadCount,badges.unreadTotal(r.wx));assert.ok(instance.data.unreadCount>0);
 p.updatePreferences(r.wx,{messages:false,interactions:false,announcements:false});instance.refreshUnread();assert.equal(instance.data.unreadCount,0);
 assert.equal(require('../.test-dist/features/contact/repository.js').listConversations(r.wx).length,3);
});
test('public authors open profiles while anonymous cards expose no author route',()=>{
 const r=runtime(),f=require('../.test-dist/features/forum/repository.js'),feed=require('../.test-dist/features/forum/feed.js');const state=f.readForum(r.wx);
 const {instance}=loadComponent('components/forum-post-card/index.js',r);instance.data.post=feed.toPostView(state.posts.find(p=>p.id==='post-001'),state.interactions);instance.onAuthorTap();assert.equal(r.calls.routes[0],'/pages/user-home/index?id=mock-lin');
 instance.data.post=feed.toPostView(state.posts.find(p=>p.id==='post-002'),state.interactions);assert.equal(instance.data.post.authorId,undefined);instance.onAuthorTap();assert.equal(r.calls.routes.length,1);
});
