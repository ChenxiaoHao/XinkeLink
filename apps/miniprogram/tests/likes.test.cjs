const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {noticeView}=require('../.test-dist/backend/adapters.js');
test('post like notification uses the server actor projection and legacy records keep their meaning',()=>{
 const dto={id:'1',type:'reaction',eventKind:'post_like',actor:{kind:'user',displayName:'周予安',user:{id:'2',nickname:'周予安',avatar:{url:'/avatar.png'}}},createdAt:'2026-10-30T00:00:00Z',title:'互动提醒',target:{kind:'post',id:'3',availability:'available'}};
 const current=noticeView(dto);assert.equal(current.actorName,'周予安');assert.equal(current.summary,'赞了你的校园动态');assert.ok(current.avatar.endsWith('/avatar.png'));
 assert.equal(noticeView({...dto,eventKind:'post_favorite'}).summary,'收藏了你的校园动态（历史记录）');
 assert.equal(noticeView({...dto,title:'内容已不可用',target:{...dto.target,availability:'unavailable'}}).summary,'内容已不可用');
});
test('posts expose exactly comment, repost and like actions',()=>{
 const root=path.resolve(__dirname,'../miniprogram');
 const source=fs.readFileSync(path.join(root,'components/forum-post-card/index.wxml'),'utf8');
 assert.match(source,/catchtap="onReplyTap"/); assert.match(source,/catchtap="onRepostTap"/); assert.match(source,/catchtap="onLikeTap"/);
 assert.match(source,/post\.liked/); assert.match(source,/post\.reposted/);
 // 书签与分享不再是动作行的一部分，改走「更多」菜单。
 assert.doesNotMatch(source,/onCollectTap/);
 assert.doesNotMatch(source,/open-type="share"/);
});
