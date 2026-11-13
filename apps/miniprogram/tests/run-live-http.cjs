// 手动验收入口：只连接显式启用的本地测试服务；不是微信模拟器或真机验收。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { loadPage } = require('./page-harness.cjs');
const { createHttpRuntime } = require('./http-runtime.cjs');
const { client } = require('../.test-dist/backend/client.js');

async function main() {
  assert.equal(process.env.XINKELINK_LIVE_HTTP, '1', '须显式设置XINKELINK_LIVE_HTTP=1');
  const alice = createHttpRuntime({ code: 'day5-alice' }), bob = createHttpRuntime({ code: 'day5-bob' });
  const page = (name, runtime) => loadPage(`pages/${name}/index.js`, { ...runtime, fixture: false, betaPhase: 'open' });
  await client(alice.wx).login(); await client(bob.wx).login();
  assert.ok(alice.saved.get('xinkelink.backend.session.v1')); assert.ok(bob.saved.get('xinkelink.backend.session.v1'));
  const marker = '原生Page真实HTTP验收-' + Date.now();
  const publish = page('forum-publish', alice); await publish.onLoad({});
  publish.setData({ form: { ...publish.data.form, content: marker, category: 'study', anonymous: false, images: [] } });
  await publish.onPublishTap();
  const postRoute = alice.routes.findLast(route => route.includes('forum-detail'));
  assert.ok(postRoute, JSON.stringify(alice.feedback)); const postId = new URL('http://local' + postRoute).searchParams.get('id');
  const mine = page('my-posts', alice); await mine.reload(); assert.ok(mine.data.posts.some(post => post.id === postId));
  const detail = page('forum-detail', bob); await detail.onLoad({ id: postId }); assert.equal(detail.data.post.content, marker);
  detail.setData({ commentDraft: '来自真实页面的评论', replyTarget: null }); await detail.onSubmitComment();
  await detail.saveInteraction('liked'); await detail.saveInteraction('collected');
  assert.equal(detail.data.post.liked, true); assert.equal(detail.data.post.collected, true); assert.ok(detail.data.comments.length);
  const collections = page('my-collections', bob); await collections.reload(); assert.ok(collections.data.posts.some(post => post.id === postId));
  const search = page('search', bob); await search.onLoad({}); search.setData({ query: marker }); await search.runSearch(); assert.ok(search.data.postResults.some(post => post.id === postId));
  const chat = page('contact-chat', bob); await chat.onLoad({ peerId: client(alice.wx).me.id }); assert.equal(chat.data.loadFailed, false);
  chat.setData({ input: '页面发送的真实持久消息' }); await chat.onSendTap(); assert.ok(chat.data.messages.some(message => message.text === '页面发送的真实持久消息'));
  const contact = page('contact', alice); await contact.reload(); assert.ok(contact.data.conversations.some(item => item.id === chat._conversationId));
  const profile = page('profile-edit', alice); await profile.onLoad();
  profile.setData({ form: { ...profile.data.form, nickname: '联调同学', major: '软件工程', className: '联调1班', year: '2024', studentId: 'LOCAL-ONLY' } });
  await profile.onSaveTap(); assert.equal((await client(alice.wx).request('/me')).nickname, '联调同学');
  assert.equal(alice.saved.get('xinkelink.private.student-id.v1'), 'LOCAL-ONLY');
  const forum = page('forum', bob); await forum.onLoad({}); assert.equal(forum.data.loadFailed, false);
  for (const runtime of [alice, bob]) assert.equal(runtime.calls.filter(call => call.status >= 400 || call.error).length, 0, JSON.stringify(runtime.calls.filter(call => call.status >= 400 || call.error)));
  fs.writeFileSync(path.resolve('../../.local/backend-day5/miniprogram-live-http.json'), JSON.stringify({
    mode: 'Node原生Page测试适配器+真实HTTP/MySQL，非微信运行环境', postId,
    alice: alice.calls, bob: bob.calls, feedback: [...alice.feedback, ...bob.feedback],
  }, null, 2));
  console.log('原生Page正式数据层HTTP联调通过；微信换码为测试适配器，未声明微信模拟器/真机通过。');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
