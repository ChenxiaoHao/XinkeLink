const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');
const { TABS } = require('../.test-dist/utils/navigation');
const { loadPage } = require('./page-harness.cjs');

test('five primary destinations keep discovery, AI and both inboxes independent', () => {
  assert.deepEqual(TABS.map(x => x.key), ['forum', 'search', 'ai', 'notifications', 'contact']);
  assert.equal(new Set(TABS.map(x => x.url)).size, 5);
});
test('profile drawer opens and closes without navigating', () => {
  const { instance } = loadComponent('components/app-shell/index.js');
  instance.openDrawer(); assert.equal(instance.data.opened, true);
  instance.closeDrawer(); assert.equal(instance.data.opened, false);
});
test('header requests the enclosing shell drawer instead of storing a second drawer',()=>{
 const {instance,events}=loadComponent('components/page-header/index.js');instance.openDrawer();assert.equal(events[0].name,'profilemenu');assert.equal(instance.data.drawerVisible,undefined);
});
test('bookmark emits the post identity without opening the card', () => {
  const { instance, events } = loadComponent('components/forum-post-card/index.js');
  instance.data.post = { id: 'post-42' };
  instance.onCollectTap();
  assert.equal(events.length, 1); assert.equal(events[0].name, 'collect');
  assert.equal(events[0].detail.id, 'post-42');
});
test('the home segmented bar switches between real board categories', () => {
  const page = loadPage('pages/forum/index.js', { wx: { getStorageSync() {}, pageScrollTo() {} } });
  assert.equal(page.data.activeCategory, 'recommended');
  assert.equal(page.data.activeLabel, '推荐');
  assert.equal(page.data.categories.length, 8);
  page.onCategoryTap({ currentTarget: { dataset: { category: 'lost-found' } } });
  assert.equal(page.data.activeCategory, 'lost-found');
  assert.equal(page.data.activeLabel, '失物认领');
  // 没有后端支撑的维度不进分段栏：没有「关注」。
  assert.equal(page.data.categories.some(item => item.key === 'following'), false);
  page.onCategoryTap({ currentTarget: { dataset: { category: 'not-a-board' } } });
  assert.equal(page.data.activeCategory, 'lost-found');
});
test('notifications open directly on notifications while private inbox opens on messages', () => {
  const notices = loadPage('pages/notifications/index.js');
  const messages = loadPage('pages/contact/index.js');
  assert.equal(notices.data.activeTab,'notifications');
  assert.equal(messages.data.activeTab,'messages');
});
test('drawer only accepts its declared destinations and closes before navigation', () => {
  const routes=[];
  const { instance }=loadComponent('components/app-shell/index.js',{wx:{navigateTo:o=>routes.push(o.url)}});
  instance.data.opened=true;
  instance.onNavigate({currentTarget:{dataset:{route:'../../secret'}}});
  assert.equal(routes.length,0); assert.equal(instance.data.opened,true);
  instance.onNavigate({currentTarget:{dataset:{route:'profile'}}});
  assert.equal(routes[0],'/pages/profile/index'); assert.equal(instance.data.opened,false);
});
test('reply action on a detail card opens its composer without pushing another detail page', () => {
  const { instance,events }=loadComponent('components/forum-post-card/index.js',{wx:{navigateTo(){throw Error('duplicate detail navigation')}}});
  instance.data.post={id:'p-1'};instance.data.detail=true;instance.onReplyTap();
  assert.equal(events[0].name,'reply');
});
test('text draft excludes temporary media that would expire before a later visit', () => {
  const values=new Map();
  const page=loadPage('pages/forum-publish/index.js',{wx:{setStorageSync:(k,v)=>values.set(k,v),showToast(){}}});
  page.data.form.content='留到下次发布';page.data.form.images=['/tmp/ephemeral.jpg'];page.onSaveDraft();
  assert.equal(values.get('xinkelink.post.draft.v1').content,'留到下次发布');
  assert.equal(values.get('xinkelink.post.draft.v1').images.length,0);
});
test('drawer removes native tab controls until dismissal, including swipe dismissal', () => {
  const bar={data:{drawerVisible:false},setData(patch){Object.assign(this.data,patch)}};
  const { instance }=loadComponent('components/app-shell/index.js',{pages:[{getTabBar:()=>bar}]});
  instance.openDrawer(); assert.equal(bar.data.drawerVisible,true);
  instance.onTouchStart({changedTouches:[{clientX:200,clientY:20}]});
  instance.onTouchEnd({changedTouches:[{clientX:100,clientY:20}]});
  assert.equal(instance.data.opened,false); assert.equal(bar.data.drawerVisible,false);
});
