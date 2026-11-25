const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {loadPage}=require('./page-harness.cjs');
test('normal startup reads the anonymous feed without a retired access or activity request',async()=>{
 const app=JSON.parse(fs.readFileSync('miniprogram/app.json','utf8'));assert.equal(app.pages[0],'pages/forum/index');
 const calls=[],routes=[];const wx={getStorageSync(){},setStorageSync(){},request(o){const p=new URL(o.url).pathname;calls.push(p);o.success({statusCode:200,data:{code:'OK',data:{items:[],hasMore:false}}});},showToast(){},reLaunch:o=>routes.push(o.url)};
 const page=loadPage('pages/forum/index.js',{wx,fixture:false});await page.onLoad({});await page.onShow();assert.ok(calls.includes('/api/v1/posts'));assert.ok(calls.every(path=>!path.includes('/beta/')&&!path.includes('/lucky-bags')));assert.equal(routes.length,0);page.onUnload();
 assert.ok(app.pages.every(path=>!path.includes('beta-access')&&!path.includes('lucky-bag')));
});
