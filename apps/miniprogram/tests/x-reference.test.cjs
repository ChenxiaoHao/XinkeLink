const test=require('node:test');
const assert=require('node:assert/strict');
const {loadComponent}=require('./native-harness.cjs');
test('marketplace routes and product components are absent from the application',()=>{
 const app=require('../miniprogram/app.json');
 assert.equal(app.pages.some(p=>/market|my-items/.test(p)),false);
});
test('compose opens the post editor directly without the removed sale chooser',()=>{
 const routes=[];
 const {instance}=loadComponent('custom-tab-bar/index.js',{wx:{navigateTo:o=>routes.push(o.url)}});
 instance.openPublish();assert.deepEqual(routes,['/pages/forum-publish/index']);
});
