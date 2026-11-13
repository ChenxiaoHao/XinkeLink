const test=require('node:test'),assert=require('node:assert/strict');
const repo=()=>require('../.test-dist/features/contact/repository.js');
const store=()=>{const saved=new Map();return{saved,getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v)}};
test('conversations show seeded unread counts, become read and send one local message without fake peer replies',()=>{
 const s=store(),r=repo();const before=r.listConversations(s);assert.equal(before.length,3);assert.ok(before.reduce((n,x)=>n+x.unread,0)>0);
 r.openConversation(s,'mock-ajie');assert.equal(r.listConversations(s).find(x=>x.id==='mock-ajie').unread,0);
 const a=r.readConversation(s,'mock-ajie');r.sendText(s,'mock-ajie','  你好，还在吗？  ');const b=r.readConversation(s,'mock-ajie');
 assert.equal(b.messages.length,a.messages.length+1);assert.equal(b.messages.at(-1).text,'你好，还在吗？');assert.equal(b.messages.at(-1).status,'sent');
 assert.equal(r.listConversations(s).find(x=>x.id==='mock-ajie').lastMessage,'你好，还在吗？');
});
test('current history wins over a stale view; unknown peers and corrupt state cannot be sent',()=>{
 const s=store(),r=repo();r.openConversation(s,'mock-ajie');r.sendText(s,'mock-ajie','先发送');r.sendText(s,'mock-ajie','后发送');
 assert.deepEqual(r.readConversation(s,'mock-ajie').messages.slice(-2).map(m=>m.text),['先发送','后发送']);
 assert.throws(()=>r.sendText(s,'unknown','hello'));
 s.saved.set('xinkelink.contact.v1',{version:1,threads:'bad'});let writes=0;s.setStorageSync=()=>writes++;assert.throws(()=>r.sendText(s,'mock-ajie','hello'));assert.equal(writes,0);
});
test('read-all persists inbox state without removing conversation histories',()=>{
 const s=store(),r=repo();r.markAllConversationsRead(s);assert.equal(r.listConversations(s).reduce((n,x)=>n+x.unread,0),0);
 assert.equal(r.listConversations(s).length,3);
});
test('a mock introductory message never jumps after persisted messages on a later app start',()=>{
 const s=store(),r=repo();const old=Date.now()-30*86400000;
 s.saved.set('xinkelink.contact.v1',{version:1,threads:{'mock-ajie':{messages:[{id:'contact-history',text:'之前发送的消息',createdAt:old,fromSelf:true,status:'sent'}],readIds:[]}}});
 assert.equal(r.readConversation(s,'mock-ajie').messages.at(-1).text,'之前发送的消息');
});
test('a public forum author can start an empty direct conversation and anonymous private IDs cannot',()=>{
 const s=store(),r=repo();r.openConversation(s,'mock-zhou');assert.equal(r.readConversation(s,'mock-zhou').messages.length,0);assert.ok(r.listConversations(s).some(c=>c.id==='mock-zhou'));assert.throws(()=>r.openConversation(s,'private-author-02'));
});
