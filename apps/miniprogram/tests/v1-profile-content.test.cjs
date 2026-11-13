const test=require('node:test'),assert=require('node:assert/strict');
function runtime(){const saved=new Map();return{saved,getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v)}}
test('profile lists and collections use existing IDs, own anonymous posts stay out of public profiles',()=>{
 const s=runtime(),f=require('../.test-dist/features/forum/repository.js'),c=require('../.test-dist/features/profile/content.js');
 const p=f.publishForumPost(s,{content:'匿名记录',images:[],category:'campus',anonymous:true},[]);
 assert.equal(c.getMyPosts(s).some(x=>x.id===p.id),true);assert.equal(c.getPublicProfile(s,'local-student').posts.some(x=>x.id===p.id),false);
 f.togglePostInteraction(s,p.id,'collected');assert.ok(c.getCollections(s).posts.some(x=>x.id===p.id));f.deleteOwnPost(s,p.id);assert.equal(c.getCollections(s).posts.some(x=>x.id===p.id),false);
 assert.throws(()=>c.getPublicProfile(s,'private-author-02'));
});
