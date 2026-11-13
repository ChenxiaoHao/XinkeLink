const test=require('node:test'),assert=require('node:assert/strict');
const repo=()=>require('../.test-dist/features/profile/repository.js');
const store=()=>{const saved=new Map();return{saved,getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v)}};
test('profile and preferences validate self-reported fields without asserting official verification',()=>{
 const s=store(),r=repo();const p=r.readProfile(s);r.saveProfile(s,{...p,nickname:'陈同学',bio:'校园生活',major:'软件工程',className:'计科22-1班',studentId:'2022001234',year:'2022'});
 assert.equal(r.readProfile(s).nickname,'陈同学');assert.equal(r.localIdentity(s).nickname,'陈同学');
 assert.equal(r.readProfile(s).className,'计科22-1班');assert.equal(r.readProfile(s).studentId,'2022001234');assert.equal('school' in r.readProfile(s),false);
 assert.throws(()=>r.saveProfile(s,{...p,nickname:' '}));assert.throws(()=>r.saveProfile(s,{...p,bio:'x'.repeat(41)}));
 r.updatePreferences(s,{messages:false});assert.equal(r.readPreferences(s).messages,false);assert.equal(r.readPreferences(s).interactions,true);
 r.setLocalSession(s,false);assert.equal(r.readLocalSession(s),false);r.setLocalSession(s,true);assert.equal(r.readLocalSession(s),true);
});
test('legacy profiles keep major and year while replacing school with empty class and private student ID',()=>{
 const s=store(),r=repo();s.saved.set(r.PROFILE_KEY,{version:1,nickname:'旧同学',avatar:'/avatar.jpg',bio:'旧简介',school:'旧学校',major:'计算机科学',year:'2023'});
 const migrated=r.readProfile(s);assert.equal(migrated.major,'计算机科学');assert.equal(migrated.year,'2023');assert.equal(migrated.className,'');assert.equal(migrated.studentId,'');assert.equal('school' in migrated,false);
 r.saveProfile(s,{...migrated,studentId:'2023009988'});assert.equal(r.readProfile(s).studentId,'2023009988');
});
test('corrupt saved profile cannot be overwritten by a fallback and guest mutation asks local login',()=>{
 const s=store(),r=repo();s.saved.set(r.PROFILE_KEY,{nickname:123});assert.throws(()=>r.saveProfile(s,r.defaultProfile()));
 let modal;s.saved.set(r.SESSION_KEY,false);assert.equal(r.requireLocalSession({...s,showModal:x=>modal=x,showToast(){}}),false);assert.ok(modal.content.includes('登录'));
});
test('own post deletion synchronizes feed and detail while profile edits project onto existing nonanonymous authors',()=>{
 const s=store(),r=repo(),f=require('../.test-dist/features/forum/repository.js');
 const post=f.publishForumPost(s,{content:'我的第一条动态',images:[],category:'campus',anonymous:false},[]);
 r.saveProfile(s,{...r.readProfile(s),nickname:'新昵称'});assert.equal(f.readForum(s).posts.find(p=>p.id===post.id).author.nickname,'新昵称');
 assert.throws(()=>f.deleteOwnPost(s,'post-001'));f.deleteOwnPost(s,post.id);assert.equal(f.readForum(s).posts.some(p=>p.id===post.id),false);
});
