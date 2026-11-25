const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const history = require('../.test-dist/features/search/history.js');
const { publishForumPost, updateOwnPost, togglePostInteraction } = require('../.test-dist/features/forum/repository.js');
function setup(loggedIn = true) {
 const values = new Map([['xinkelink.session.v1', loggedIn]]), toasts = [], routes = [];
 const wx = { __fixtureMode:true, env:{USER_DATA_PATH:'/user'}, getStorageSync:k=>values.get(k),setStorageSync:(k,v)=>values.set(k,v),showToast:o=>toasts.push(o.title),showModal(){},navigateTo:o=>routes.push(o.url),navigateBack:()=>routes.push('back'),redirectTo:o=>routes.push(o.url) };
 return {values,wx,toasts,routes};
}
const form = content => ({content,category:'campus',anonymous:false,images:[]});
test('guest history remains hidden and mutations leave preserved owner data untouched',()=>{
 const r=setup();history.addSearchHistory(r.wx,'私密词','posts');const before=JSON.stringify([...r.values]);
 r.values.set('xinkelink.session.v1',false);assert.deepEqual(history.readSearchHistory(r.wx),[]);
 history.addSearchHistory(r.wx,'访客','posts');history.clearSearchHistory(r.wx);history.removeSearchHistory(r.wx,'私密词','posts');
 r.values.set('xinkelink.session.v1',true);assert.equal(JSON.stringify([...r.values]),before);
 assert.equal(history.readSearchHistory(r.wx)[0].query,'私密词');
});
test('guest cannot recover editId content or saved drafts and cannot save AI draft',()=>{
 const r=setup();const post=publishForumPost(r.wx,form('本人私密编辑'),[]);r.values.set('xinkelink.post.draft.v1',form('草稿私密'));
 r.values.set('xinkelink.session.v1',false);
 const page=loadPage('pages/forum-publish/index.js',r);page.onLoad({editId:post.id,draft:'1'});assert.equal(page.data.form.content,'');assert.equal(page.data.editId,post.id);
 const drafts=loadPage('pages/drafts/index.js',r);drafts.onShow();assert.equal(drafts.data.postDraft,'');
 const ai=loadPage('pages/ai/index.js',r);assert.equal(ai.onPostTap,undefined,'AI has no draft or publish action');assert.equal(r.values.get('xinkelink.post.draft.v1').content,'草稿私密');assert.equal(r.routes.length,0);
});
test('edit anonymity remains immutable and unavailable repost never writes interactions',()=>{
 const r=setup();const post=publishForumPost(r.wx,form('原文'),[]);const updated=updateOwnPost(r.wx,post.id,{...form('改文'),anonymous:true},[]);assert.equal(updated.anonymousNumber,undefined);
 assert.throws(()=>togglePostInteraction(r.wx,post.id,'reposted'),/转发/);assert.equal(r.values.has('xinkelink.forum.interactions.v1'),false);
});
test('search rejects stale result ids and catches interaction persistence errors',()=>{
 const r=setup();const page=loadPage('pages/search/index.js',r);page.setData({query:'校园'});page.runSearch();
 const snapshot=JSON.stringify(page.data.postResults);const postId=page.data.postResults[0].id;
 page.savePostInteraction('not-in-results','liked');assert.equal(r.values.has('xinkelink.forum.interactions.v1'),false);
 r.wx.setStorageSync=()=>{throw Error('full')};assert.doesNotThrow(()=>page.savePostInteraction(postId,'liked'));assert.equal(JSON.stringify(page.data.postResults),snapshot);
});
test('history save failure still searches and preserves the visible history',()=>{
 const r=setup();const page=loadPage('pages/search/index.js',r);page.setData({query:'校园',history:[{query:'旧词',type:'posts',searchedAt:1}]});
 r.wx.setStorageSync=()=>{throw Error('full')};assert.doesNotThrow(()=>page.onSubmit());assert.equal(page.data.hasSearched,true);assert.equal(page.data.history[0].query,'旧词');
 assert.doesNotThrow(()=>page.onClearHistory());assert.equal(page.data.history[0].query,'旧词');
 assert.doesNotThrow(()=>page.onRemoveHistory({currentTarget:{dataset:{index:0}}}));assert.equal(page.data.history[0].query,'旧词');
});
test('new and edit drafts stay separate and publishing clears only the active draft',()=>{
 const r=setup();const {savePostDraft,readPostDraft}=require('../.test-dist/features/forum/drafts.js');
 const first=publishForumPost(r.wx,form('原文甲'),[]),second=publishForumPost(r.wx,{...form('原文乙'),anonymous:true},[]);
 savePostDraft(r.wx,form('新建草稿'));savePostDraft(r.wx,form('编辑甲'),first.id);savePostDraft(r.wx,form('编辑乙'),second.id);
 const page=loadPage('pages/forum-publish/index.js',r);page.onLoad({editId:first.id,draft:'1'});assert.equal(page.data.form.content,'编辑甲');page.onAnonymousTap();assert.equal(page.data.form.anonymous,false);
 page.onPublishTap();assert.equal(readPostDraft(r.wx,first.id),null);assert.equal(readPostDraft(r.wx).content,'新建草稿');assert.equal(readPostDraft(r.wx,second.id).content,'编辑乙');
 const edit=loadPage('pages/forum-publish/index.js',r);edit.onLoad({editId:second.id});edit.onAnonymousChange({detail:{value:false}});assert.equal(edit.data.form.anonymous,true);
});
test('live private storage isolates accounts from fixture legacy values and keeps logout recovery',()=>{
 const r=setup();const {SESSION_STORAGE_KEY}=require('../.test-dist/backend/config.js');const {client}=require('../.test-dist/backend/client.js');
 const {savePostDraft,readPostDraft}=require('../.test-dist/features/forum/drafts.js');savePostDraft(r.wx,form('fixture旧草稿'));history.addSearchHistory(r.wx,'fixture词','posts');
 function live(id){return {...r.wx,__fixtureMode:false,getStorageSync:k=>k===SESSION_STORAGE_KEY?{accessToken:'a',refreshToken:'r',expiresAt:Date.now()+10000,me:{id}}:r.values.get(k)}}
 const a=live('a'),b=live('b');assert.equal(readPostDraft(a),null);assert.deepEqual(history.readSearchHistory(a),[]);
 savePostDraft(a,form('A草稿'));history.addSearchHistory(a,'A词','posts');assert.equal(readPostDraft(b),null);assert.deepEqual(history.readSearchHistory(b),[]);
 savePostDraft(b,form('B草稿'));assert.equal(readPostDraft(a).content,'A草稿');client(a).clear();assert.equal(readPostDraft(a),null);assert.deepEqual(history.readSearchHistory(a),[]);
 const resumed=live('a');assert.equal(readPostDraft(resumed).content,'A草稿');assert.equal(history.readSearchHistory(resumed)[0].query,'A词');assert.equal(readPostDraft(r.wx).content,'fixture旧草稿');
});
test('failed edit publish and cancelled leave preserve the saved draft for reentry',()=>{
 const r=setup();const {savePostDraft,readPostDraft}=require('../.test-dist/features/forum/drafts.js');
 const post=publishForumPost(r.wx,form('原文'),[]);savePostDraft(r.wx,form('待恢复正文'),post.id);savePostDraft(r.wx,form('新建保留'));
 const page=loadPage('pages/forum-publish/index.js',r);page.onLoad({editId:post.id});let modal;r.wx.showModal=o=>{modal=o};page.onCancelTap();modal.success({confirm:false});assert.equal(page.data.form.content,'待恢复正文');assert.equal(r.routes.length,0);
 const write=r.wx.setStorageSync;r.wx.setStorageSync=(k,v)=>{if(k==='xinkelink.forum.posts.v2')throw Error('full');write(k,v)};page.onPublishTap();assert.equal(page._published,false);assert.equal(readPostDraft(r.wx,post.id).content,'待恢复正文');assert.equal(readPostDraft(r.wx).content,'新建保留');
 page.onCancelTap();modal.success({confirm:true});const reentry=loadPage('pages/forum-publish/index.js',r);reentry.onLoad({editId:post.id});assert.equal(reentry.data.form.content,'待恢复正文');
});
test('leaving the session clears open editor and history while persisted data recovers after login',()=>{
 const r=setup();const {savePostDraft}=require('../.test-dist/features/forum/drafts.js');savePostDraft(r.wx,form('保存的私密正文'));history.addSearchHistory(r.wx,'私密检索','posts');
 const editor=loadPage('pages/forum-publish/index.js',r);editor.onLoad({draft:'1'});assert.equal(editor.data.form.content,'保存的私密正文');const search=loadPage('pages/search/index.js',r);search.onShow();assert.equal(search.data.history.length,1);
 r.values.set('xinkelink.session.v1',false);editor.onShow();search.onShow();assert.equal(editor.data.form.content,'');assert.equal(editor.data.loadFailed,true);assert.equal(search.data.history.length,0);
 r.values.set('xinkelink.session.v1',true);const fresh=loadPage('pages/forum-publish/index.js',r);fresh.onLoad({draft:'1'});assert.equal(fresh.data.form.content,'保存的私密正文');search.onShow();assert.equal(search.data.history[0].query,'私密检索');
});

test('search input invalidates old results before another interaction can write',()=>{const r=setup();const p=loadPage('pages/search/index.js',r);p.setData({query:'校园'});p.runSearch();const id=p.data.postResults[0].id;p.onInput({detail:{value:'新关键词'}});p.savePostInteraction(id,'liked');assert.equal(r.values.has('xinkelink.forum.interactions.v1'),false);assert.equal(p.data.postResults.length,0);});
test('historical study category remains editable with its own label',()=>{const r=setup();const post=publishForumPost(r.wx,{...form('历史学习帖'),category:'study'},[]);const p=loadPage('pages/forum-publish/index.js',r);p.onLoad({editId:post.id});assert.equal(p.data.form.category,'study');assert.match(p.data.categoryLabel,/学习/);});
