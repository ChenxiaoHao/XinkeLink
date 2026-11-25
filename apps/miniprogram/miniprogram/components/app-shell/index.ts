import {getViewerRole} from '../../features/profile/viewer'
import {client,Runtime} from '../../backend/client'
import {sessionAvatar} from '../../backend/preview'
import {imageDue} from '../../backend/image-urls'
import {Preferences,localIdentity,requireLocalSession,readPreferences,saveProfile,setLocalSession,updatePreferences} from '../../features/profile/repository'
import {persistProfileAvatar,releaseProfileAvatar} from '../../features/profile/avatar'

const PREFERENCE_KEYS = ['messages','interactions','announcements','sound'] as const
type PreferenceKey = typeof PREFERENCE_KEYS[number]
type PreferencePatch = Partial<Omit<Preferences,'version'>>
const identityLoads = new WeakMap<object, object>()
const touchStarts = new WeakMap<object, { clientX: number; clientY: number }>()

// 抽屉即设置中心：账号卡 + 通知偏好 + 隐私/反馈/关于 + 退出登录都在这里，
// 独立设置页与书签页已下线。
Component({
 data:{opened:false,statusHeight:47,
   identity:{nickname:'访客',avatar:'/assets/icons/user-round-muted.png',handle:'',dynamicCount:0,receivedLikeCount:0},
   links:[{label:'个人资料',icon:'user-round',route:'profile'}],
   preferences:{version:1,messages:true,interactions:true,announcements:true,sound:false} as Preferences,
   notifyOpen:false,notifySummary:'全部开启',loggedIn:false,busy:false,
   authVisible:false,authSaving:false,authError:''},
 lifetimes:{
  attached(){try{this.setData({statusHeight:wx.getWindowInfo().statusBarHeight||47})}catch{}},
  detached(){identityLoads.delete(this)},
 },
 pageLifetimes:{hide(){this.closeDrawer()}},
 methods:{
  async refreshIdentity(){
   const load = {}
   identityLoads.set(this, load)
   // 本地演示模式：client() 会抛异常，必须在进入 try 之前单独处理身份与会话。
   if((wx as Runtime).__fixtureMode===true){
    const loggedIn=getViewerRole(wx)!=='guest'
    let nickname='访客',avatar='/assets/icons/user-round-muted.png'
    if(loggedIn){
     try{const local=localIdentity(wx);nickname=local.nickname||nickname;avatar=local.avatar||avatar}catch{}
    }
    this.setData({identity:{nickname,avatar,handle:'',dynamicCount:0,receivedLikeCount:0},loggedIn})
    this.loadPreferences()
    return
   }
   if(getViewerRole(wx)==='guest'){this.setData({loggedIn:false,identity:{nickname:'访客',avatar:'/assets/icons/user-round-muted.png',handle:'',dynamicCount:0,receivedLikeCount:0}});this.loadPreferences();return}
   try{
    const api=client(wx),userId=api.me?.id
    const placeholder='/assets/icons/user-round-muted.png'
    const prepared=sessionAvatar(api)
    const current=()=>identityLoads.get(this)===load&&api.loggedIn&&api.me?.id===userId
    this.setData({identity:{nickname:api.me?.nickname||'访客',avatar:prepared.url||placeholder,handle:api.me?.handle||'',dynamicCount:0,receivedLikeCount:0},loggedIn:true})
    await Promise.all([
     api.request('/me/summary').then(summary=>{
      if(current())this.setData({identity:{...this.data.identity,dynamicCount:Number(summary.postCount)||0,receivedLikeCount:Number(summary.receivedLikeCount)||0}})
     }).catch(()=>{}),
     prepared.renewal?.then(avatar=>{
      if(avatar&&current())this.setData({identity:{...this.data.identity,avatar}})
     }),
     this.loadPreferences(),
    ])
   }catch{}
  },
  async loadPreferences(){
   if(getViewerRole(wx)==='guest'){this.applyPreferences({version:1,messages:true,interactions:true,announcements:true,sound:false});return}
   try{
    const fixture=(wx as Runtime).__fixtureMode===true
    if(fixture){this.applyPreferences(readPreferences(wx));return}
    const api=client(wx)
    if(!api.loggedIn)return
    const userId=api.me?.id,preferences=await api.request('/me/preferences')
    if(api.loggedIn&&api.me?.id===userId)this.applyPreferences(preferences)
   }catch{}
  },
  // 通知组默认折叠，用一行摘要表示四项开关的整体状态。
  applyPreferences(preferences:Preferences){
   const values=[preferences.messages,preferences.interactions,preferences.announcements,preferences.sound]
   const on=values.filter(Boolean).length
   this.setData({preferences,notifySummary:on===values.length?'全部开启':on===0?'全部关闭':'部分开启'})
  },
  onNotifyToggle(){this.setData({notifyOpen:!this.data.notifyOpen})},
  async onPreferenceChange(e:WechatMiniprogram.CustomEvent<{value:boolean}>){
   const key=e.currentTarget.dataset.key as PreferenceKey
   if(!PREFERENCE_KEYS.some(item=>item===key))return
   if((wx as Runtime).__fixtureMode===true){
    if(!requireLocalSession(wx)){this.setData({preferences:{...this.data.preferences}});return}
    try{updatePreferences(wx,{[key]:e.detail.value} as PreferencePatch);this.applyPreferences(readPreferences(wx))}
    catch{this.setData({preferences:{...this.data.preferences}});wx.showToast({title:'设置未保存，请重试',icon:'none'})}
    return
   }
   const api=client(wx)
   try{
    if(!await api.requireSession()){this.setData({preferences:{...this.data.preferences}});return}
    const preferences=await api.request('/me/preferences','PATCH',{[key]:e.detail.value,expectedVersion:this.data.preferences.version})
    this.applyPreferences(preferences)
   }catch{wx.showToast({title:'设置未保存，请重试',icon:'none'})}
  },
  onPrivacyTap(){this.closeDrawer();wx.navigateTo({url:'/pages/legal/index?tab=privacy',fail(){wx.showModal({title:'用户协议与隐私政策',content:'专业、班级和入学年份由用户自填，未经平台核实。学号仅保存在本机，不展示在公开主页。请勿公开敏感个人信息。',showCancel:false,confirmText:'知道了'})}})},
  onAboutTap(){wx.showModal({title:'关于信科 Link',content:'信科 Link · 连接校园里的每一种生活。当前支持校园动态、评论互动、通知与同学私信。',showCancel:false,confirmText:'知道了'})},
  // 抽屉头像（可能是历史遗留的临时路径）加载失败时退回内置占位，避免破图。
  onIdentityAvatarError(event?: WechatMiniprogram.CustomEvent){
   const source=event?.currentTarget.dataset.source
   if(source&&source!==this.data.identity.avatar)return
   if(imageDue(this.data.identity.avatar)){void this.refreshIdentity();return}
   if(this.data.identity.avatar!=='/assets/icons/user-round-muted.png')this.setData({'identity.avatar':'/assets/icons/user-round-muted.png'})
  },
  onFeedbackTap(){this.closeDrawer();wx.navigateTo({url:'/pages/feedback/index',fail(){wx.showToast({title:'反馈页面暂时无法打开',icon:'none'})}})},
  // 抽屉里的登录入口：弹出微信头像昵称授权；登录态持久化，第二次无需再授权。
  onLoginTap(){this.setData({authVisible:true,authSaving:false,authError:''})},
  onAuthCancel(){if(this.data.authSaving)return;this.setData({authVisible:false,authError:''})},
  async onAuthConfirm(e:WechatMiniprogram.CustomEvent<{nickname?:string;avatarPath?:string}>){
   if(this.data.authSaving)return
   const nickname=typeof e.detail?.nickname==='string'?e.detail.nickname.trim():''
   const avatarPath=e.detail?.avatarPath
   if(!nickname||nickname.length>20||typeof avatarPath!=='string'||!avatarPath){this.setData({authError:'请选择头像并填写昵称'});return}
   this.setData({authSaving:true,authError:''})
   try{
    if((wx as Runtime).__fixtureMode===true){
     // chooseAvatar 给的是临时文件路径（http://tmp/... / wxfile://...），
     // 直接存进 profile 会在重启或临时目录被清理后破图，必须先落到 USER_DATA_PATH。
     const persisted=persistProfileAvatar(wx,avatarPath)
     try{
      saveProfile(wx,{version:1,nickname,avatar:persisted.avatar,bio:'',major:'',className:'',studentId:'',year:''})
      setLocalSession(wx,true)
     }catch(error){
      if(persisted.created)releaseProfileAvatar(wx,persisted.created)
      throw error
     }
     this.setData({authVisible:false});this.refreshIdentity();return
    }
    const api=client(wx)
    await api.login(true)
    const image=await api.upload(avatarPath,'avatar'),current=await api.request('/me')
    const updated=await api.request('/me','PATCH',{nickname,avatarImageId:image.id,expectedVersion:current.version})
    api.setMe(updated,true)
    this.setData({authVisible:false});this.refreshIdentity()
   }catch(error){this.setData({authError:error instanceof Error?error.message:'头像昵称保存失败，请重试'})}
   finally{this.setData({authSaving:false})}
  },
  onLogoutTap(){
   if(this.data.busy)return
   wx.showModal({title:'退出登录？',content:'已发布的内容、收藏和资料会保留，退出后可继续以访客身份浏览。',confirmText:'退出登录',success:result=>{
    if(!result.confirm)return
    if((wx as Runtime).__fixtureMode===true){
     try{setLocalSession(wx,false);this.setData({loggedIn:false});this.refreshIdentity()}catch{wx.showToast({title:'退出未完成，请重试',icon:'none'})}
     return
    }
    this.setData({busy:true})
    client(wx).logout().catch(()=>{}).then(()=>{this.setData({loggedIn:false,busy:false});this.refreshIdentity()})
   }})
  },
  openDrawer(){if(this.data.opened)return;this.setData({opened:true});this.setTabShift(true);void this.refreshIdentity()},
  closeDrawer(){if(!this.data.opened)return;this.setData({opened:false});this.setTabShift(false)},
  setTabShift(open:boolean){const pages=getCurrentPages();pages[pages.length-1]?.getTabBar?.()?.setData({drawerVisible:open});this.triggerEvent('drawerchange',{opened:open})},
  stopTap(){},
  onTouchStart(event:WechatMiniprogram.TouchEvent){
   const touch=event.changedTouches[0]
   if(touch)touchStarts.set(this,{clientX:touch.clientX,clientY:touch.clientY})
  },
  onTouchEnd(event:WechatMiniprogram.TouchEvent){
   const start=touchStarts.get(this),end=event.changedTouches[0]
   touchStarts.delete(this)
   if(!start||!end)return
   const dx=end.clientX-start.clientX,dy=end.clientY-start.clientY
   if(Math.abs(dx)<=60||Math.abs(dx)<=Math.abs(dy)*1.5)return
   if(dx<0)this.closeDrawer()
   else if(start.clientX<24)this.openDrawer()
  },
  async onNavigate(e:WechatMiniprogram.TouchEvent){const routes:Record<string,string>={profile:'/pages/profile/index','my-posts':'/pages/my-posts/index'};const url=routes[String(e.currentTarget.dataset.route||'')];if(!url)return;if((wx as Runtime).__fixtureMode===true){if(!requireLocalSession(wx))return}else if(!await client(wx).requireSession())return;this.closeDrawer();wx.navigateTo({url})},
 }
})
