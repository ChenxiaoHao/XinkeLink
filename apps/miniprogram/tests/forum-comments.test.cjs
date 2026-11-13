const test=require('node:test'), assert=require('node:assert/strict');
const {loadPage}=require('./page-harness.cjs');
const repo=()=>require('../.test-dist/features/forum/repository.js');
const comments=()=>require('../.test-dist/features/forum/comments.js');
function runtime(){const saved=new Map(),calls={toasts:[],modals:[]};const wx={getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v),showToast:x=>calls.toasts.push(x.title),showModal:x=>calls.modals.push(x),hideKeyboard(){}};return{saved,calls,wx};}
function event(id){return{currentTarget:{dataset:{id}}};}
test('initial comment records and projected counts agree for every mock post',()=>{
 const r=runtime(),s=repo().readForum(r.wx);
 for(const p of s.posts)assert.equal(comments().visibleComments(p,s.commentState).length,p.comments);
 assert.equal(s.posts.find(p=>p.id==='post-001').comments,12);
});
test('comments, two-level replies and likes survive reload and reject invalid or foreign targets',()=>{
 const r=runtime(),id=repo().addForumComment(r.wx,'post-001','  一级评论  ');
 const reply=repo().addForumComment(r.wx,'post-001','二级回复',id);
 const second=repo().addForumComment(r.wx,'post-001','回复二级',reply);
 let s=repo().readForum(r.wx),p=s.posts.find(p=>p.id==='post-001'),all=comments().visibleComments(p,s.commentState);
 assert.equal(p.comments,15);assert.equal(all.find(c=>c.id===second).parentId,id);
 assert.equal(all.find(c=>c.id===second).replyToId,reply);
 repo().toggleForumCommentLike(r.wx,'post-001',reply);s=repo().readForum(r.wx);
 assert.equal(comments().commentThreads(p,s.commentState,[],[id]).find(c=>c.id===id).replies.find(c=>c.id===reply).liked,true);
 assert.throws(()=>repo().addForumComment(r.wx,'post-002','跨帖',id));
 assert.throws(()=>repo().addForumComment(r.wx,'missing','test'));
 assert.throws(()=>repo().addForumComment(r.wx,'post-001',' '));
 assert.throws(()=>repo().addForumComment(r.wx,'post-001','a'.repeat(1001)));
});
test('only own comments may be deleted and deleting a root also removes its replies from the count',()=>{
 const r=runtime(),id=repo().addForumComment(r.wx,'post-001','我的评论');repo().addForumComment(r.wx,'post-001','回复',id);
 assert.throws(()=>repo().deleteOwnForumComment(r.wx,'post-001','post-001-comment-0'));
 repo().deleteOwnForumComment(r.wx,'post-001',id);const s=repo().readForum(r.wx),p=s.posts.find(x=>x.id==='post-001');
 assert.equal(p.comments,12);assert.equal(comments().visibleComments(p,s.commentState).some(c=>c.id===id||c.parentId===id),false);
 assert.throws(()=>repo().addForumComment(r.wx,'post-001','失效回复',id));
});
test('anonymous post author stays anonymous in comment and reply projections',()=>{
 const r=runtime(),s=repo().readForum(r.wx),p=s.posts.find(p=>p.id==='post-002');
 const projected=comments().commentThreads(p,s.commentState,[],['post-002-comment-1']);
 assert.equal(JSON.stringify(projected).includes('private-author'),false);
 assert.equal(JSON.stringify(projected).includes('不应公开'),false);
 assert.ok(JSON.stringify(projected).includes('匿名同学 023'));
});
test('unreadable or malformed comment state blocks every mutation without replacing data',()=>{
 const r=runtime();r.saved.set('xinkelink.forum.comments.v1',{version:1,added:'broken'});let writes=0;r.wx.setStorageSync=()=>writes++;
 assert.throws(()=>repo().addForumComment(r.wx,'post-001','评论'));assert.throws(()=>repo().toggleForumCommentLike(r.wx,'post-001','post-001-comment-0'));
 assert.equal(writes,0);
});
test('comment page supports expand, reply, submit, local counts and confirmed own deletion',()=>{
 const r=runtime(),p=loadPage('pages/forum-detail/index.js',r);p.onLoad({id:'post-001'});
 const long=p.data.comments.find(c=>c.canExpand);assert.ok(long);p.onExpandComment(event(long.id));assert.equal(p.data.comments.find(c=>c.id===long.id).expanded,true);
 p.onReplyTap(event(p.data.comments[0].id));assert.ok(p.data.replyTarget);p.onCommentInput({detail:{value:'这是一条本地回复'}});p.onSubmitComment();assert.equal(p.data.post.commentCount,13);assert.equal(p.data.commentDraft,'');
 const parent=p.data.comments.find(c=>c.replyCount>0);p.onExpandReplies(event(parent.id));assert.ok(p.data.comments.find(c=>c.id===parent.id).replies.length>0);
 p.onOpenComposer();p.onCommentInput({detail:{value:'自己的一级评论'}});p.onSubmitComment();const own=p.data.comments.find(c=>c.isMine);assert.ok(own);
 p.onDeleteComment(event(own.id));r.calls.modals[0].success({confirm:false});assert.equal(p.data.post.commentCount,14);
 p.onDeleteComment(event(own.id));r.calls.modals[1].success({confirm:true});assert.equal(p.data.post.commentCount,13);
});
test('failed comment write keeps input and does not increment detail or home counts',()=>{
 const r=runtime(),p=loadPage('pages/forum-detail/index.js',r);p.onLoad({id:'post-001'});p.onCommentInput({detail:{value:'不要丢失'}});
 r.wx.setStorageSync=()=>{throw new Error('quota')};p.onSubmitComment();assert.equal(p.data.commentDraft,'不要丢失');assert.equal(p.data.post.commentCount,12);assert.equal(p.data.sending,false);
});
