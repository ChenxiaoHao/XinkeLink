const { BACKEND_ORIGIN: configuredOrigin } = require('../.test-dist/backend/config.js');
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { loadPage } = require('./page-harness.cjs');
const { client, ApiClient } = require('../.test-dist/backend/client.js');
const sessionKey = 'xinkelink.backend.session.v1';
const me = { id:'91',nickname:'数据库昵称',avatar:{imageId:'8',url:'/api/v1/images/8/content?grant=test'},bio:'真实简介',major:'计算机',className:'1班',enrollmentYear:'2025',version:3 };
const summary = {postCount:4,favoritePostCount:3,likedPostCount:3,receivedLikeCount:7};
function runtime(overrides={}) {
 const saved=new Map(),calls=[],routes=[],toasts=[];let loginCalls=0;
 const ok=(o,data)=>o.success({statusCode:200,data:{code:'OK',data}});
 const wx={getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v),removeStorageSync:k=>saved.delete(k),
 login:o=>{loginCalls++;o.success({code:'wx-one-time-code'});},
 request:o=>{calls.push(o);const pathname=new URL(o.url).pathname.replace('/api/v1','');
   if(overrides[pathname])return overrides[pathname](o,ok);
   if(pathname==='/auth/wechat'||pathname==='/auth/refresh')return ok(o,{accessToken:'access',refreshToken:'refresh',expiresIn:900,me});
   if(pathname==='/me')return ok(o,me);
   if(pathname==='/me/summary')return ok(o,summary);
   if(pathname==='/me/preferences')return ok(o,{messages:true,interactions:true,announcements:true,sound:false,version:0});
   if(pathname==='/auth/logout')return ok(o,{revoked:true});
   o.success({statusCode:404,data:{code:'NOT_FOUND',message:'未找到'}});
 },showToast:o=>toasts.push(o.title),navigateTo:o=>routes.push(o.url),redirectTo:o=>routes.push(o.url),switchTab:o=>routes.push(o.url),
 showModal:o=>o.success({confirm:true}),stopPullDownRefresh(){},hideKeyboard(){}};
 return {wx,saved,calls,routes,toasts,get loginCalls(){return loginCalls;}};
}
// 此组验证认证传输与续接；真实资料弹层由 profile-auth-sheet 测试单独覆盖。
const page=(name,r)=>{const p=loadPage(`pages/${name}/index.js`,{...r,fixture:false,betaPhase:'open'});p.requestProfileConsent=async()=>{await client(r.wx).login();return true};return p};

test('guest profile says 登录; main card and login button sign in on the same page with real profile and summary',async()=>{
 const r=runtime(),p=page('profile',r);await p.onShow();assert.equal(p.data.profile.nickname,'登录');assert.equal(r.calls.length,0);
 await Promise.all([p.onHomeTap(),p.onLoginTap()]);assert.equal(r.loginCalls,1);assert.equal(p.data.loggedIn,true);assert.equal(p.data.profile.nickname,me.nickname);assert.equal(p.data.profile.avatar,configuredOrigin+me.avatar.url);assert.equal(p.data.postCount,4);assert.equal(p.data.collectionCount,3);assert.deepEqual(r.routes,[]);assert.deepEqual(r.calls.find(o=>o.url.endsWith('/auth/wechat')).data,{code:'wx-one-time-code'});
 p.onUnload();
});
test('failed login stays on the profile, retains 登录 and shows the server error',async()=>{
 const r=runtime({'/auth/wechat':o=>o.success({statusCode:502,data:{code:'WECHAT_LOGIN_FAILED',message:'微信登录暂不可用'}})}),p=page('profile',r);await p.onShow();await p.onLoginTap();
 assert.equal(p.data.profile.nickname,'登录');assert.equal(p.data.loggedIn,false);assert.equal(p.data.loadFailed,false);assert.equal(r.saved.get(sessionKey),undefined);assert.deepEqual(r.routes,[]);assert.ok(r.toasts.some(text=>text.includes('微信登录暂不可用')));assert.equal(p.data.loggingIn,false);p.onUnload();
});
test('missing server configuration is shown explicitly without pretending login succeeded',async()=>{
 const r=runtime({'/auth/wechat':o=>o.success({statusCode:503,data:{code:'WECHAT_NOT_CONFIGURED',message:'微信登录尚未配置，请联系管理员'}})}),p=page('profile',r);await p.onShow();await p.onLoginTap();assert.equal(p.data.loggedIn,false);assert.equal(p.data.profile.nickname,'登录');assert.ok(r.toasts.includes('微信登录尚未配置，请联系管理员'));assert.deepEqual(r.routes,[]);p.onUnload();
});
test('a protected like signs in inline and continues the intended operation exactly once',async()=>{
 let liked=false;const dto={id:'51',category:'campus',content:'公开帖子',images:[],author:{id:'92',nickname:'作者',avatar:null,isAnonymous:false},createdAt:'2026-10-25T00:00:00.000Z',likeCount:2,commentCount:0,favorited:false,version:0};
 const r=runtime({'/posts':(o,ok)=>ok(o,{items:[{...dto,liked,likeCount:liked?3:2}],nextCursor:null,hasMore:false}),'/announcements':(o,ok)=>ok(o,{items:[]}),'/posts/51/like':(o,ok)=>{liked=o.method==='PUT';ok(o,{liked,likeCount:3});}});
 const p=page('forum',r);await p.onLoad({});await p.saveInteraction('51','liked');assert.equal(r.loginCalls,1);assert.equal(p.data.posts[0].liked,true);assert.equal(r.calls.filter(o=>o.url.includes('/posts/51/like')).length,1);assert.deepEqual(r.routes,[]);
});
test('failed inline login does not execute a protected mutation or navigate away',async()=>{
 const r=runtime({'/auth/wechat':o=>o.fail({errMsg:'offline'})});const p=page('forum-detail',r);p._postId='66';p.setData({post:{id:'66',collected:false}});await p.onCollectTap();
 assert.equal(r.calls.some(o=>o.url.includes('/favorite')),false);assert.deepEqual(r.routes,[]);assert.equal(p.data.post.collected,false);assert.equal(r.toasts.length,1);
});
test('profile protected menu continues to its intended page after inline login',async()=>{
 const r=runtime(),p=page('profile',r);await p.onShow();await p.onMyPostsTap();assert.equal(p.data.profile.nickname,me.nickname);assert.deepEqual(r.routes,['/pages/my-posts/index']);p.onUnload();
});
test('concurrent protected operations share one wx.login and code exchange',async()=>{
 const r=runtime();let login;r.wx.login=o=>login=o;const api=new ApiClient(r.wx);const first=api.requireSession(),second=api.requireSession();assert.ok(login);login.success({code:'same-code'});assert.deepEqual(await Promise.all([first,second]),[true,true]);assert.equal(r.calls.filter(o=>o.url.endsWith('/auth/wechat')).length,1);assert.deepEqual(r.routes,[]);
});
test('logout during an unfinished login cannot restore a stale signed-in session',async()=>{
 const r=runtime();let login;r.wx.login=o=>login=o;const api=new ApiClient(r.wx);const pending=api.login();api.clear();login.success({code:'stale-code'});await assert.rejects(pending);assert.equal(api.loggedIn,false);assert.equal(r.saved.get(sessionKey),null);
});
test('expired session uses refresh and retains the current profile; invalid refresh restores 登录',async()=>{
 const r=runtime();r.saved.set(sessionKey,{accessToken:'expired',refreshToken:'refresh',expiresAt:Date.now()-1,me});const p=page('profile',r);await p.onShow();assert.equal(r.calls.filter(o=>o.url.endsWith('/auth/refresh')).length,1);assert.equal(r.loginCalls,0);assert.equal(p.data.profile.nickname,me.nickname);
 client(r.wx).clear();await Promise.resolve();assert.equal(p.data.profile.nickname,'登录');p.onUnload();
});
test('a rejected refresh resets the visible profile without silently starting another login',async()=>{
 const r=runtime({'/auth/refresh':o=>o.success({statusCode:401,data:{code:'REFRESH_INVALID',message:'登录已失效'}})});r.saved.set(sessionKey,{accessToken:'expired',refreshToken:'revoked',expiresAt:Date.now()-1,me});const p=page('profile',r);await p.onShow();assert.equal(p.data.profile.nickname,'登录');assert.equal(p.data.loggedIn,false);assert.equal(p.data.loadFailed,false);assert.equal(r.loginCalls,0);assert.equal(client(r.wx).loggedIn,false);assert.deepEqual(r.routes,[]);p.onUnload();
});
test('native avatar and nickname are saved through avatar upload and versioned profile PATCH',async()=>{
 let patch,uploaded;const r=runtime({'/me':(o,ok)=>{if(o.method==='PATCH'){patch=o.data;ok(o,{...me,nickname:o.data.nickname,avatar:{imageId:o.data.avatarImageId,url:'/new-avatar'},version:4});}else ok(o,me);}});r.saved.set(sessionKey,{accessToken:'a',refreshToken:'r',expiresAt:Date.now()+600000,me});r.wx.navigateBack=()=>{};
 r.wx.uploadFile=o=>{uploaded=o;o.success({statusCode:201,data:JSON.stringify({code:'OK',data:{id:'new-avatar'}})});};
 const p=page('profile-edit',r);await p.onLoad();p.onChooseAvatar({detail:{avatarUrl:'/tmp/my-avatar.png'}});p.onNicknameBlur({detail:{value:'用户选的昵称'}});await p.onSaveTap();
 assert.equal(uploaded.filePath,'/tmp/my-avatar.png');assert.equal(uploaded.formData.purpose,'avatar');assert.equal(patch.avatarImageId,'new-avatar');assert.equal(patch.expectedVersion,3);assert.equal(patch.nickname,'用户选的昵称');assert.equal('studentId' in patch,false);assert.equal(client(r.wx).me.nickname,'用户选的昵称');
});
test('native wx.login failure preserves the page and protected read does not call its API',async()=>{
 const r=runtime();r.wx.login=o=>o.fail({errMsg:'native unavailable'});const p=page('my-posts',r);await p.reload();assert.equal(p.data.loadFailed,true);assert.equal(r.calls.length,0);assert.deepEqual(r.routes,[]);assert.ok(r.toasts.length);
});
test('notification read action also uses the common inline login guard',async()=>{
 const r=runtime({'/me/conversations/read':(o,ok)=>ok(o,{watermarks:[]}),'/conversations':(o,ok)=>ok(o,{items:[]}),'/notifications':(o,ok)=>ok(o,{items:[]}),'/me/unread':(o,ok)=>ok(o,{raw:{messages:0,comments:0,reactions:0,announcements:0},badgeTotal:0})});const p=page('contact',r);await p.onMarkAllRead();assert.equal(r.loginCalls,1);assert.ok(r.calls[0].url.endsWith('/auth/wechat'));assert.equal(r.calls.find(o=>o.url.endsWith('/me/conversations/read')).header.Authorization,'Bearer access');assert.deepEqual(r.routes,[]);
});
test('old login route is unregistered and production source has no navigation references to it',()=>{
 const root=path.resolve(__dirname,'../miniprogram');const app=JSON.parse(fs.readFileSync(path.join(root,'app.json'),'utf8'));assert.equal(app.pages.includes('pages/login/index'),false);
 function visit(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){const full=path.join(dir,entry.name);if(entry.isDirectory())visit(full);else if(/\.(ts|wxml)$/.test(entry.name))assert.equal(fs.readFileSync(full,'utf8').includes('/pages/login/index'),false,full);}}
 visit(root);
});
test('profile editor requests avatar and nickname through explicit native controls',async()=>{
 const root=path.resolve(__dirname,'../miniprogram');const source=fs.readFileSync(path.join(root,'pages/profile-edit/index.wxml'),'utf8');assert.match(source,/open-type="chooseAvatar"/);assert.match(source,/bindchooseavatar="onChooseAvatar"/);assert.match(source,/type="nickname"/);
 const r=runtime();r.saved.set(sessionKey,{accessToken:'a',refreshToken:'r',expiresAt:Date.now()+600000,me});const p=page('profile-edit',r);await p.onLoad();const before=r.calls.length;p.onChooseAvatar({detail:{avatarUrl:'/tmp/chosen-avatar.png'}});p.onNicknameBlur({currentTarget:{dataset:{field:'nickname'}},detail:{value:'用户自选昵称'}});assert.equal(p.data.form.avatar,'/tmp/chosen-avatar.png');assert.equal(p.data.form.nickname,'用户自选昵称');assert.equal(r.calls.length,before);
});
