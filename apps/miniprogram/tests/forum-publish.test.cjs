const test=require('node:test'), assert=require('node:assert/strict');
const {loadPage}=require('./page-harness.cjs');const {loadComponent}=require('./native-harness.cjs');
const valid=()=>({content:'分享今天的校园生活',category:'campus',anonymous:false,images:['/tmp/campus.jpg']});
function runtime(overrides={}){const saved=new Map([['xinkelink.session.v1',true]]),calls={routes:[],toasts:[],copied:[],removed:[],modals:[]};const wx={getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v),env:{USER_DATA_PATH:'/user'},getFileSystemManager:()=>({copyFileSync:(a,b)=>calls.copied.push([a,b]),unlinkSync:p=>calls.removed.push(p)}),showToast:x=>calls.toasts.push(x.title),redirectTo:x=>calls.routes.push(x.url),navigateBack:x=>calls.routes.push('back'),showModal:x=>calls.modals.push(x),...overrides};const page=loadPage('pages/forum-publish/index.js',{wx});return{page,saved,calls,wx};}
test('post validation permits text-only posts but requires content, known category, boolean anonymous and at most nine images',()=>{
 const {validatePostDraft}=require('../.test-dist/features/forum/publish.js');assert.equal(validatePostDraft({...valid(),images:[]}),null);
 for(const patch of [{content:' '},{content:'x'.repeat(2001)},{category:'recommended'},{anonymous:'yes'},{images:Array(10).fill('/tmp/a')},{images:['']}])assert.equal(typeof validatePostDraft({...valid(),...patch}),'string');
});
test('publishing anonymous post persists images once, hides author everywhere, routes by ID and appears in home',()=>{
 const r=runtime();r.page.setData({form:{...valid(),anonymous:true}});r.page.onPublishTap();r.page.onPublishTap();
 const posts=r.saved.get('xinkelink.forum.posts.v2');assert.equal(posts.length,1);assert.equal(r.calls.copied.length,1);assert.equal(posts[0].author.id,'local-student');
 assert.ok(posts[0].images[0].startsWith('/user/xlk-forum-upload-'));assert.equal(r.calls.routes[0],'/pages/forum-detail/index?id='+posts[0].id);
 const home=loadPage('pages/forum/index.js',r);home.onLoad({});assert.equal(home.data.posts[0].id,posts[0].id);assert.match(home.data.posts[0].authorName,/匿名同学/);
 assert.equal(JSON.stringify(home.data.posts).includes('local-student'),false);
 const detail=loadPage('pages/forum-detail/index.js',r);detail.onLoad({id:posts[0].id});detail.onLikeTap();home.onShow();assert.equal(home.data.posts[0].likeCount,1);
 detail.onCommentInput({detail:{value:'自己的匿名回复'}});detail.onSubmitComment();home.onShow();assert.equal(home.data.posts[0].commentCount,1);assert.match(detail.data.comments[0].authorName,/匿名同学/);
});
test('failed post storage preserves input, rolls back only new files and never reports success',()=>{
 const r=runtime({setStorageSync(){throw Error('full')}});r.page.setData({form:valid()});r.page.onPublishTap();assert.equal(r.calls.routes.length,0);assert.equal(r.page.data.form.content,valid().content);assert.equal(r.page.data.publishing,false);assert.equal(r.calls.removed[0],r.calls.copied[0][1]);assert.equal(r.calls.toasts.includes('发布成功'),false);
});
test('post selection guards busy chooser, limits nine pictures, handles cancellation and supports removal',()=>{
 let options;const r=runtime({chooseMedia:o=>{options=o;}});r.page.onAddImages();r.page.onAddImages();assert.equal(options.count,9);options.success({tempFiles:[{size:100,tempFilePath:'/tmp/a.jpg'},{size:11000000,tempFilePath:'/tmp/large.jpg'}]});assert.equal(r.page.data.form.images.length,1);r.page.onRemoveImage({currentTarget:{dataset:{index:0}}});assert.equal(r.page.data.form.images.length,0);r.page.onAddImages();options.fail({errMsg:'chooseMedia:fail cancel'});assert.equal(r.page._choosing,false);
});
test('leaving a nonempty post asks confirmation and cancelling retains the form',()=>{
 const r=runtime();r.page.setData({form:valid()});r.page.onCancelTap();r.calls.modals[0].success({confirm:false});assert.equal(r.calls.routes.length,0);assert.equal(r.page.data.form.content,valid().content);r.page.onCancelTap();r.calls.modals[1].success({confirm:true});assert.equal(r.calls.routes[0],'back');
});
test('header back can be intercepted without changing the forum fallback',()=>{
 const routes=[];const {instance,events}=loadComponent('components/back-header/index.js',{wx:{navigateBack:o=>{routes.push('back');o.fail();},navigateTo:o=>routes.push(o.url),switchTab:o=>routes.push(o.url)}});
 instance.data.interceptBack=true;instance.onBack();assert.equal(events[0].name,'requestback');assert.equal(routes.length,0);
 instance.data.interceptBack=false;instance.onBack();assert.equal(routes[1],'/pages/forum/index');
});
