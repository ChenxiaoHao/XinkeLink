const { BACKEND_ORIGIN: configuredOrigin } = require('../.test-dist/backend/config.js');
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const corePath = path.resolve(__dirname, '../.test-dist/backend/client.js');
test('the production transport exists independently of fixture repositories', () => {
  assert.equal(fs.existsSync(corePath), true, 'a real transport is required');
});
const { ApiClient, sequence, compareSequence } = require('../.test-dist/backend/client.js');
const { mergeMessages, media } = require('../.test-dist/backend/adapters.js');
const { BackendService } = require('../.test-dist/backend/service.js');
test('conversation time displays complete hours and minutes without truncating locale text',()=>{
 const service=new BackendService({});const date=new Date(2026,9,26,15,8,30);
 const original=Date.prototype.toLocaleTimeString;Date.prototype.toLocaleTimeString=()=> '下午3:08:30';
 try { const item=service.conversationView({id:'1',peer:{nickname:'同学'},lastMessage:null,updatedAt:date.toISOString(),unreadCount:0});assert.equal(item.timeLabel,'15:08'); }
 finally { Date.prototype.toLocaleTimeString=original; }
});
function sessionRuntime(handler) {
 const store=new Map([['xinkelink.backend.session.v1',{accessToken:'old',refreshToken:'refresh-old',expiresAt:Date.now()+600000,me:{id:'1'}}]]);
 const calls=[]; const runtime={ getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v),removeStorageSync:k=>store.delete(k),
 request:o=>{calls.push(o);handler(o);}};
 return {runtime,calls,store};
}
function ok(o,data){o.success({statusCode:200,data:{code:'OK',data}})}
function unauthorized(o){o.success({statusCode:401,data:{code:'TOKEN_EXPIRED',message:'expired'}})}
test('simultaneous expired requests refresh once and replay with the rotated access token',async()=>{
 let refresh;const r=sessionRuntime(o=>{if(o.url.endsWith('/auth/refresh')) refresh=o;else if(o.header.Authorization==='Bearer old')unauthorized(o);else ok(o,{id:'ok'});});
 const api=new ApiClient(r.runtime),a=api.request('/me'),b=api.request('/me/summary');
 await Promise.resolve();assert.equal(r.calls.filter(o=>o.url.endsWith('/auth/refresh')).length,1);
 ok(refresh,{accessToken:'new',refreshToken:'refresh-new',expiresIn:900,me:{id:'1'}});
 assert.deepEqual(await Promise.all([a,b]),[{id:'ok'},{id:'ok'}]);assert.equal(r.store.get('xinkelink.backend.session.v1').refreshToken,'refresh-new');
});
test('refresh rejection clears credentials and does not turn a delayed authenticated request into a guest read',async()=>{
 let delayed;const r=sessionRuntime(o=>{if(o.url.endsWith('/auth/refresh'))unauthorized(o);else if(o.url.endsWith('/posts') && o.header.Authorization)delayed=o;else if(o.url.endsWith('/posts'))ok(o,[]);else unauthorized(o);});
 const api=new ApiClient(r.runtime),pending=api.request('/posts');await assert.rejects(api.request('/me'));
 unauthorized(delayed);await assert.rejects(pending);assert.equal(api.loggedIn,false);
 assert.equal(r.calls.filter(o=>o.url.endsWith('/posts')).length,1,'must never anonymously retry');
});
test('network and business errors are surfaced without retrying a fixture or discarding a valid session',async()=>{
 const r=sessionRuntime(o=>o.fail({errMsg:'offline'}));const api=new ApiClient(r.runtime);
 await assert.rejects(api.request('/posts'),error=>error.code==='NETWORK_ERROR');assert.equal(r.calls.length,1);assert.equal(api.loggedIn,true);
});
test('decimal sequence validation and HTTP reconciliation retain uint64 precision and overwrite hidden messages',()=>{
 assert.equal(compareSequence('9007199254740993','9007199254740992'),1);assert.equal(sequence('18446744073709551615'),'18446744073709551615');
 for(const invalid of [1,'01','-1','18446744073709551616'])assert.throws(()=>sequence(invalid));
 const merged=mergeMessages([{id:'m',sequence:'9007199254740993',text:'old'}],[{id:'b',sequence:'9007199254740992',text:'first'},{id:'m',sequence:'9007199254740993',text:'消息已不可用'}]);
 assert.deepEqual(merged.map(m=>m.id),['b','m']);assert.equal(merged[1].text,'消息已不可用');
});
test('retries reuse already uploaded image IDs so an uncertain publish can use the original idempotency key',async()=>{
 let uploads=0;const r=sessionRuntime(()=>{});const api=new ApiClient(r.runtime);r.runtime.uploadFile=o=>{uploads++;o.success({statusCode:201,data:JSON.stringify({code:'OK',data:{id:'image-'+uploads}})})};
 const service=new BackendService(api);assert.deepEqual(await service.images(['/temp/a.jpg'],'post'),['image-1']);assert.deepEqual(await service.images(['/temp/a.jpg'],'post'),['image-1']);assert.equal(uploads,1);
});

test('media grants preserve configured origin and opaque IDs',()=>{assert.equal(media({url:'/image'}),configuredOrigin+'/image');});
