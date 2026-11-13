// 显式运行：node tests/backend-http-flow.cjs；要求仅测试classpath服务器已启动。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { loadPage } = require('./page-harness.cjs');
const { createHttpRuntime } = require('./http-runtime.cjs');
const { client } = require('../.test-dist/backend/client.js');
const touch = (id, action) => ({ currentTarget: { dataset: { id, action } } });
async function run() {
  const origin = process.env.XINKELINK_TEST_ORIGIN || 'http://127.0.0.1:18081';
  const alice = createHttpRuntime({ origin, code: 'day5-alice' }), bob = createHttpRuntime({ origin, code: 'day5-bob' });
  const page = (name, runtime) => loadPage(`pages/${name}/index.js`, { ...runtime, fixture: false, betaPhase: 'open' });
  const idFromRoute = runtime => new URL('http://test' + runtime.routes.at(-1)).searchParams.get('id');
  const tag = 'Day5小程序HTTP-' + Date.now();
  const fixture = { tag, origin, boundary: '真实HTTP/JWT/MySQL/Redis；wx.login使用测试源码适配器，未验收真实微信或真机' };
  const reportDir = path.resolve(__dirname, '../.local/backend-day5');fs.mkdirSync(reportDir, { recursive: true });
  const check = label => console.log('PASS ' + label);
  try {
    for (const runtime of [alice,bob]) { await client(runtime.wx).login(); assert.ok(client(runtime.wx).loggedIn, runtime.feedback.join(',')); }
    fixture.aliceId=client(alice.wx).me.id;fixture.bobId=client(bob.wx).me.id;check('explicit test login -> real HTTP session');
    const publish=page('forum-publish',alice);publish.setData({form:{content:tag,category:'help',anonymous:true,images:[]}});await publish.onPublishTap();
    fixture.postId=idFromRoute(alice);assert.ok(fixture.postId);check('anonymous post publishing');
    const detail=page('forum-detail',bob);await detail.onLoad({id:fixture.postId});assert.equal(detail.data.post.authorId,undefined);
    detail.setData({commentDraft:'HTTP评论 '+tag});await detail.onSubmitComment();assert.ok(detail.data.comments.length);fixture.commentId=detail.data.comments[0].id;
    await detail.saveInteraction('liked');assert.equal(detail.data.post.liked,true);await detail.saveInteraction('collected');assert.equal(detail.data.post.collected,true);check('detail comments, like and favorite');
    const ownDetail=page('forum-detail',alice);await ownDetail.onLoad({id:fixture.postId});ownDetail.setData({commentDraft:'HTTP回复',replyTarget:{id:fixture.commentId,name:'同学'}});await ownDetail.onSubmitComment();ownDetail._openedReplies=[fixture.commentId];await ownDetail.reload();assert.equal(ownDetail.data.comments[0].replies.length,1);
    await ownDetail.onCommentLike({detail:{id:fixture.commentId}});assert.equal(ownDetail.data.comments[0].liked,true);check('reply pagination and comment like');
    const editPost=page('forum-publish',alice);await editPost.onLoad({editId:fixture.postId});editPost.setData({form:{...editPost.data.form,content:tag+' 修改'}});await editPost.onPublishTap();await detail.reload();assert.equal(detail.data.post.content,tag+' 修改');check('post editing preserves ID and anonymity');
    const feed=page('forum',bob);await feed.onLoad({});assert.ok(feed.data.posts.some(post=>post.id===fixture.postId));
    const search=page('search',bob);await search.runSearch(tag);assert.ok(search.data.postResults.some(post=>post.id===fixture.postId));check('feed and unified search');
    const image=path.resolve(__dirname,'../miniprogram/assets/icons/plus-white.png');
    const chat=page('contact-chat',bob);await chat.onLoad({peerId:fixture.aliceId});assert.equal(chat.data.canSend,true);chat.setData({input:'HTTP私信 '+tag});await chat.onSendTap();assert.equal(chat.data.messages.length,1);fixture.conversationId=chat._conversationId;
    const aliceChat=page('contact-chat',alice);await aliceChat.onLoad({conversationId:fixture.conversationId});assert.equal(aliceChat.data.messages[0].fromSelf,false);check('direct chat persists conversation and string reads');
    const chatSettings=page('chat-settings',bob);await chatSettings.onLoad({conversationId:fixture.conversationId});await chatSettings.onPreferenceChange({currentTarget:{dataset:{key:'pinned'}},detail:{value:true}});assert.equal(chatSettings.data.preferences.pinned,true);
    await chatSettings.onPreferenceChange({currentTarget:{dataset:{key:'blocked'}},detail:{value:true}});await chat.reload();assert.equal(chat.data.canSend,false);
    await chatSettings.onPreferenceChange({currentTarget:{dataset:{key:'blocked'}},detail:{value:false}});await chatSettings.onHistoryInput({detail:{value:'HTTP私信'}});assert.equal(chatSettings.data.historyMatches.length,1);
    await chatSettings.onClearHistoryTap();await chat.reload();assert.equal(chat.data.messages.length,0);await aliceChat.reload();assert.equal(aliceChat.data.messages.length,1);check('pin/block/history search/per-user clear');
    const profileEdit=page('profile-edit',alice);await profileEdit.onLoad();profileEdit.setData({form:{...profileEdit.data.form,nickname:'联调同学',bio:'HTTP联调自填资料',major:'软件工程',className:'测试班',year:'2025',studentId:'LOCAL-ONLY',avatar:image}});await profileEdit.onSaveTap();assert.equal(client(alice.wx).me.nickname,'联调同学');assert.ok(client(alice.wx).me.avatar.imageId);
    const profile=page('profile',alice);await profile.reload();assert.equal(profile.data.profile.nickname,'联调同学');
    const user=page('user-home',bob);await user.onLoad({id:fixture.aliceId});assert.equal(user.data.profile.posts.some(post=>post.id===fixture.postId),false);assert.equal('studentId' in user.data.profile,false);check('profile/avatar/summary/public anonymity and local-only student ID');
    const posts=page('my-posts',alice);await posts.reload();assert.ok(posts.data.posts.some(post=>post.id===fixture.postId));check('personal content');
    const inbox=page('contact',alice);await inbox.reload();assert.ok(inbox.data.notifications.length);await inbox.onMarkAllRead();const notifications=page('notifications',alice);await notifications.reload();await notifications.onMarkAllRead();assert.equal(notifications.data.notificationUnread,0);check('inbox, notifications, all-read and unread');
    const report=page('report',bob);await report.onLoad({kind:'post',id:fixture.postId});report.setData({reason:report.data.reasons[4],detail:'HTTP测试举报 '+tag});await report.onSubmitReport();assert.equal(bob.calls.at(-1).code,'OK');fixture.reportCreated=true;check('confirmed report submission');
    await detail.reload();await detail.onDeleteComment({detail:{id:fixture.commentId}});assert.equal(detail.data.comments.length,0);check('versioned comment deletion');
    const throwaway=page('forum-publish',alice);throwaway.setData({form:{content:tag+' 删除验证',category:'help',anonymous:false,images:[]}});await throwaway.onPublishTap();const deleteId=idFromRoute(alice);await posts.reload();await posts.onDeleteTap(touch(deleteId));assert.equal(posts.data.posts.some(post=>post.id===deleteId),false);check('versioned post deletion');
    fixture.result='PASS';
  } catch(error) { fixture.result='FAIL';fixture.failure=error.message;throw error; }
  finally { fixture.calls={alice:alice.calls,bob:bob.calls};fixture.feedback={alice:alice.feedback,bob:bob.feedback};fs.writeFileSync(path.join(reportDir,'http-flow.json'),JSON.stringify(fixture,null,2));console.log(JSON.stringify({result:fixture.result,postId:fixture.postId,conversationId:fixture.conversationId,evidence:path.join(reportDir,'http-flow.json')})); }
}
run().catch(error=>{console.error(error.stack);process.exitCode=1;});
