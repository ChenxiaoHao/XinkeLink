const { BACKEND_ORIGIN: configuredOrigin } = require('../.test-dist/backend/config.js');
const test=require('node:test'),assert=require('node:assert/strict');
const {previewForumImages}=require('../.test-dist/features/forum/media.js');
for(const [name,preview] of [['forum',previewForumImages]]){
 test(name+' renews backend image grants before preview and preserves the selected image',async()=>{const seen=[],requests=[],toasts=[];const api={getStorageSync(){},request:o=>{requests.push(o.url);o.success({statusCode:200,data:{code:'OK',data:{url:'/api/v1/images/9/content?grant=fresh'}}})},previewImage:o=>seen.push(o),showToast:o=>toasts.push(o.title)};
  await preview(api,[configuredOrigin + "/api/v1/images/9/content?grant=expired",'wxfile://local-image'],0);
  assert.equal(requests.length,1);assert.equal(requests[0],configuredOrigin + "/api/v1/images/9/access");assert.equal(seen[0].current,configuredOrigin + "/api/v1/images/9/content?grant=fresh");assert.equal(seen[0].urls[1],'wxfile://local-image');assert.equal(toasts.length,0);
 });
 test(name+' never falls back to an expired or unauthorized image after renewal fails',async()=>{let opened=false;const toasts=[];const api={getStorageSync(){},request:o=>o.success({statusCode:403,data:{code:'FORBIDDEN',message:'无权访问'}}),previewImage:()=>opened=true,showToast:o=>toasts.push(o.title)};
  await preview(api,[configuredOrigin + "/api/v1/images/9/content?grant=expired"],0);assert.equal(opened,false);assert.equal(toasts.length,1);
 });
 test(name+' does not send local temporary paths or unrelated URLs to the backend',async()=>{let requested=false,shown;const api={request:()=>requested=true,previewImage:o=>shown=o,showToast(){}};await preview(api,['wxfile://my-photo','https://example.invalid/local.jpg'],1);assert.equal(requested,false);assert.equal(shown.current,'https://example.invalid/local.jpg')});
}
