import {client,Runtime} from '../../backend/client'
import {profileView} from '../../backend/adapters'
import {BackendService} from '../../backend/service'
import {Preferences,localIdentity,readLocalSession,readPreferences,saveProfile,setLocalSession,updatePreferences} from '../../features/profile/repository'
import {persistProfileAvatar,releaseProfileAvatar} from '../../features/profile/avatar'

const PREFERENCE_KEYS = ['messages','interactions','announcements','sound'] as const
type PreferenceKey = typeof PREFERENCE_KEYS[number]
type PreferencePatch = Partial<Omit<Preferences,'version'>>

// 抽屉即设置中心：账号卡 + 通知偏好 + 隐私/反馈/关于 + 退出登录都在这里，
// 独立设置页与书签页已下线。
Component({
 data:{opened:false,statusHeight:47,startX:0,startY:0,
   identity:{nickname:'访客',avatar:'/assets/icons/user-round-muted.png',handle:'',dynamicCount:0,receivedLikeCount:0},
   links:[{label:'个人资料',icon:'user-round',route:'profile'}],
   preferences:{version:1,messages:true,interactions:true,announcements:true,sound:false} as Preferences,
   notifyOpen:false,notifySummary:'全部开启',loggedIn:true,busy:false,
   authVisible:false,authSaving:false,authError:''},
 lifetimes:{attached(){try{this.setData({statusHeight:wx.getWindowInfo().statusBarHeight||47})}catch{}}},
 pageLifetimes:{hide(){this.closeDrawer()},show(){this.refreshIdentity()}},
 methods:{
  async refreshIdentity(){
   // 本地演示模式：client() 会抛异常，必须在进入 try 之前单独处理身份与会话。
   if((wx as Runtime).__fixtureMode===true){
    let loggedIn=true
    try{loggedIn=readLocalSession(wx)}catch{}
    let nickname='访客',avatar='/assets/icons/user-round-muted.png'
    if(loggedIn){
     try{const local=localIdentity(wx);nickname=local.nickname||nickname;avatar=local.avatar||avatar}catch{}
    }
    this.setData({identity:{nickname,avatar,handle:'',dynamicCount:0,receivedLikeCount:0},loggedIn})
    this.loadPreferences()
    return
   }
   try{
    const api=client(wx),p=api.me?profileView(api.me):null
    let dynamicCount=0,receivedLikeCount=0
    if(api.loggedIn){
     try{const summary=await new BackendService(api).profileSummary();dynamicCount=Number(summary.postCount)||0;receivedLikeCount=Number(summary.likeCount)||0}catch{}
    }
    this.setData({identity:{nickname:p?.nickname||'访客',avatar:p?.avatar||'/assets/icons/user-round-muted.png',handle:api.me?.handle||'',dynamicCount,receivedLikeCount},loggedIn:api.loggedIn})
   }catch{}
   this.loadPreferences()
  },
  async loadPreferences(){
   try{
    const fixture=(wx as Runtime).__fixtureMode===true
    if(fixture){this.applyPreferences(readPreferences(wx));return}
    const api=client(wx)
    if(!api.loggedIn)return
    this.applyPreferences(await api.request('/me/preferences'))
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
    try{updatePreferences(wx,{[key]:e.detail.value} as PreferencePatch);this.applyPreferences(readPreferences(wx))}
    catch{this.setData({preferences:{...this.data.preferences}});wx.showToast({title:'设置未保存，请重试',icon:'none'})}
    return
   }
   const api=client(wx)
   if(!await api.requireSession())return
   try{
    const preferences=await api.request('/me/preferences','PATCH',{[key]:e.detail.value,expectedVersion:this.data.preferences.version})
    this.applyPreferences(preferences)
   }catch{wx.showToast({title:'设置未保存，请重试',icon:'none'})}
  },
  onPrivacyTap(){wx.showModal({title:'资料与隐私说明',content:'资料与通知偏好保存在当前设备。专业、班级和入学年份由用户自填，未经平台核实。学号仅保存在本机，不展示在公开主页。请勿公开敏感个人信息。',showCancel:false,confirmText:'知道了'})},
  onAboutTap(){wx.showModal({title:'关于信科 Link',content:'信科 Link · 连接校园里的每一种生活。当前支持校园动态、评论互动、通知与同学私信。',showCancel:false,confirmText:'知道了'})},
  // 抽屉头像（可能是历史遗留的临时路径）加载失败时退回内置占位，避免破图。
  onIdentityAvatarError(){if(this.data.identity.avatar!=='/assets/icons/user-round-muted.png')this.setData({'identity.avatar':'/assets/icons/user-round-muted.png'})},
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
     try{setLocalSession(wx,false);this.setData({loggedIn:false})}catch{wx.showToast({title:'退出未完成，请重试',icon:'none'})}
     return
    }
    this.setData({busy:true})
    client(wx).logout().catch(()=>{}).then(()=>{this.setData({loggedIn:false,busy:false});this.refreshIdentity()})
   }})
  },
  openDrawer(){this.refreshIdentity();this.setData({opened:true});this.setTabShift(true)},
  closeDrawer(){this.setData({opened:false});this.setTabShift(false)},
  setTabShift(open:boolean){const pages=getCurrentPages();pages[pages.length-1]?.getTabBar?.()?.setData({drawerVisible:open})},
  stopTap(){},onTouchStart(e:WechatMiniprogram.TouchEvent){this.setData({startX:e.changedTouches[0].clientX,startY:e.changedTouches[0].clientY})},
  onTouchEnd(e:WechatMiniprogram.TouchEvent){const dx=e.changedTouches[0].clientX-this.data.startX,dy=e.changedTouches[0].clientY-this.data.startY;if(Math.abs(dx)>60&&Math.abs(dx)>Math.abs(dy)*1.5){if(dx<0)this.closeDrawer();else if(this.data.startX<24)this.openDrawer()}},
  onNavigate(e:WechatMiniprogram.TouchEvent){const route=e.currentTarget.dataset.route;if(!['profile','account'].includes(route))return;this.closeDrawer();wx.navigateTo({url:route==='profile'?'/pages/profile/index':'/pages/account-feature/index?section=account'})},
 }
})
