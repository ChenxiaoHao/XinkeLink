const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const {loadPage}=require('./page-harness.cjs');
function setup(live){
  const store=new Map([[live?'xinkelink.backend.session.v1':'xinkelink.session.v1',live?{accessToken:'a',refreshToken:'r',expiresAt:Date.now()+600000,me:{id:'17',nickname:'陈浩',avatar:null}}:true]]);
  const wx={getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v),showToast(){}};
  return {page:loadPage('pages/forum-publish/index.js',{wx,fixture:!live}),store};
}
function identityText(data){
  const wxml=fs.readFileSync('miniprogram/pages/forum-publish/index.wxml','utf8');
  const expression=wxml.match(/<text class="compose-identity-label">{{(.*?)}}<\/text>/)?.[1];
  assert.ok(expression,'identity chip must expose the actual publishing identity');
  return vm.runInNewContext(expression,data);
}
for(const live of [false,true])test(`${live?'live':'fixture'} composer visibly exposes selected category and identity and locks anonymous edits`,async()=>{
  const {page:p}=setup(live);await p.onLoad({});
  assert.ok(p.data.selfNickname);assert.equal(identityText(p.data),live?'以 陈浩 发布':'以 陈林舟 发布');
  const category=p.data.categories.findIndex(c=>c.key==='help');p.onCategoryPickerChange({detail:{value:String(category)}});
  assert.equal(p.data.form.category,'help');assert.equal(p.data.categoryLabel,'互帮互助');
  p.onAnonymousTap();assert.equal(identityText(p.data),'匿名发布');
  p.setData({editId:'existing'});p.onAnonymousTap();assert.equal(identityText(p.data),'匿名发布');
  p.setData({editId:'',publishing:true});p.onAnonymousTap();p.onCategoryPickerChange({detail:{value:'0'}});
  assert.equal(p.data.form.anonymous,true);assert.equal(p.data.form.category,'help');
});
test('category chip renders its selected label, and guest identity never names the previous owner',async()=>{
  const wxml=fs.readFileSync('miniprogram/pages/forum-publish/index.wxml','utf8');
  const expression=wxml.match(/<text class="compose-category-label">{{(.*?)}}<\/text>/)?.[1];
  assert.ok(expression);assert.equal(vm.runInNewContext(expression,{categoryLabel:'校园生活'}),'校园生活');
  const {page:p,store}=setup(false);await p.onLoad({});assert.equal(p.data.selfNickname,'陈林舟');
  store.set('xinkelink.session.v1',false);p.onShow();assert.equal(p.data.selfNickname,'');assert.equal(p.data.loadFailed,true);
  // A fresh guest load must also remain empty.
  const q=loadPage('pages/forum-publish/index.js',{wx:{getStorageSync(){},showToast(){}}});await q.onLoad({});
  assert.equal(q.data.loadFailed,true);assert.equal(q.data.selfNickname,'');
});
