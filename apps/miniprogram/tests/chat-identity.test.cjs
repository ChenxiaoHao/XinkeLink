const { BACKEND_ORIGIN: configuredOrigin } = require('../.test-dist/backend/config.js');
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {loadPage}=require('./page-harness.cjs');
function runtime(profileFails=false){const saved=new Map(),calls=[];let nickname='我的新昵称';
 const me={id:'17',nickname:'旧昵称',avatar:{url:'/expired-avatar'},bio:''};saved.set('xinkelink.backend.session.v1',{accessToken:'access',refreshToken:'refresh',expiresAt:Date.now()+600000,me});
 const wx={getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v),showToast(){},request:o=>{const route=new URL(o.url).pathname.replace('/api/v1','');calls.push(route);let data;
  if(route==='/me'){if(profileFails){o.fail({errMsg:'offline'});return}data={...me,nickname,avatar:{url:'/fresh-avatar'}}}
  else if(route==='/conversations/31')data={id:'31',peer:{id:'18',nickname:'陈屿',avatar:{url:'/peer-avatar'}}};
  else if(route==='/conversations/31/settings')data={blocked:false,pinned:false,muted:false,version:0};
  else if(route==='/conversations/31/messages')data={items:[{id:'41',sequence:'1',text:'你好',isMine:true,createdAt:'2026-10-26T00:00:00Z',visibility:'normal',persistenceStatus:'sent'}],hasMore:false,nextBeforeSequence:null};
  else if(route==='/conversations/31/read')data={readThroughSequence:'1'};
  else throw Error('unexpected '+route);
  o.success({statusCode:200,data:{code:'OK',data}});
 }};return{wx,calls,setNickname:v=>nickname=v};
}
for(const name of ['contact-chat']){
 test(name+' refreshes the current user profile instead of reusing expired avatar grants',async()=>{const r=runtime(),p=loadPage(`pages/${name}/index.js`,{wx:r.wx,fixture:false,betaPhase:'open'});await p.onLoad({conversationId:'31'});assert.equal(p.data.loadFailed,false);assert.equal(p.data.selfAvatar,configuredOrigin + "/fresh-avatar");assert.equal(p.data.selfNickname,'我的新昵称');assert.equal(p.data.peer.nickname,'陈屿');assert.equal(p.data.peer.avatar,configuredOrigin + "/peer-avatar");r.setNickname('改过的昵称');await p.reload();assert.equal(p.data.selfNickname,'改过的昵称');assert.equal(r.calls.filter(x=>x==='/me').length,2);p.onUnload()});
 test(name+' keeps readable history when profile loading fails and falls back after an avatar error',async()=>{const r=runtime(true),p=loadPage(`pages/${name}/index.js`,{wx:r.wx,fixture:false,betaPhase:'open'});await p.onLoad({conversationId:'31'});assert.equal(p.data.loadFailed,false);assert.equal(p.data.messages.length,1);assert.equal(p.data.selfNickname,'旧昵称');assert.equal(p.data.selfAvatar,'/assets/icons/user-round-muted.png');p.onChatAvatarError({currentTarget:{dataset:{owner:'peer'}}});assert.equal(p.data.peer.avatar,'/assets/icons/user-round-muted.png');p.setData({selfAvatar:'https://broken.invalid/avatar'});p.onChatAvatarError({currentTarget:{dataset:{owner:'self'}}});assert.equal(p.data.selfAvatar,'/assets/icons/user-round-muted.png');p.onUnload()});
 test(name+' renders both sender names and handles avatar image errors',()=>{const wxml=fs.readFileSync(path.resolve(__dirname,`../miniprogram/pages/${name}/index.wxml`),'utf8');assert.match(wxml,/class="chat-sender-name"/);assert.match(wxml,/item\.fromSelf\s*\?\s*selfNickname\s*:\s*peer\.nickname/);assert.match(wxml,/binderror="onChatAvatarError"/);const css=fs.readFileSync(path.resolve(__dirname,`../miniprogram/pages/${name}/index.wxss`),'utf8');assert.match(css,/\.chat-sender-name/)});
}
