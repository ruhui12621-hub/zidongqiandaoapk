(function(){
"use strict";
const $=s=>document.querySelector(s);
const view=$("#view");
let state={accounts:[],syncState:"unknown",canAddAccount:false};
let page="home",selectedId=null,messageTab="hoyolab",cloudDraft={region:"cn-east-3",ak:"",sk:""},cloudVaultLoaded=false;
const gameSelections={};
const hoyoGameOptions={cn:[["genshin","原神"],["honkai2","崩坏2"],["honkai3rd","崩坏3"],["tears_of_themis","未定事件簿"],["honkai_sr","崩坏：星穹铁道"],["zzz","绝区零"]],os:[["genshin","原神"],["honkai3rd","崩坏3"],["tears_of_themis","未定事件簿"],["honkai_sr","崩坏：星穹铁道"],["zzz","绝区零"]]};
function selectedHoyoGames(a){if(!gameSelections[a.id])gameSelections[a.id]=new Set((a.games||[]).map(g=>g.key));return gameSelections[a.id]}
const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const account=()=>state.accounts.find(x=>x.id===selectedId);
const platformInitial=p=>p==="skland"?"森":p==="miyoushe_cn"?"米":"H";
const statusClass=s=>/成功|已连接|已获取|同步/.test(s)?"ok":/失败|未获取/.test(s)?"bad":"warn";
function toast(msg){const root=$("#toast-root");root.innerHTML='<div class="toast">'+esc(msg)+'</div>';const t=root.firstElementChild;requestAnimationFrame(()=>t.classList.add("show"));setTimeout(()=>t.classList.remove("show"),2200)}
function native(name,...args){try{if(window.Android&&typeof Android[name]==="function")return Android[name](...args)}catch(e){toast("操作失败："+e.message)}}
function readState(){try{const raw=native("getState");if(raw)applyState(JSON.parse(raw))}catch(e){console.warn(e)}}
function applyState(next){if(!next)return;state=next;if(!cloudDraft.region||cloudDraft.region==="cn-east-3")cloudDraft.region=state.region||"cn-east-3";if(document.activeElement&&document.activeElement.matches("input,select,textarea"))return;render()}
window.onNativeState=raw=>{try{applyState(typeof raw==="string"?JSON.parse(raw):raw)}catch(e){console.error(e)}};
function pageTitle(title,sub){return '<div class="page-heading"><div><div class="eyebrow">HOYO CHECK-IN</div><h1>'+title+'</h1><p>'+sub+'</p></div></div>'}
function nav(){document.querySelectorAll(".nav-item").forEach(x=>x.classList.toggle("active",x.dataset.page===page))}
function renderHome(){
 const sync=state.syncLabel||"等待连接华为云";
 let html=pageTitle("账号总览","每个账号独立显示今日签到结果。");
 html+='<button class="card status-card status-card-link" data-page="cloud" aria-label="登录华为云"><div class="status-icon">☁</div><div class="status-copy"><strong>登录华为云</strong><span>'+esc(sync)+'</span></div><span class="pill '+(state.syncState==="synced"?"ok":state.syncState==="failed"?"bad":"warn")+'">'+(state.cloudConnected?"已连接":state.cloudCredentialsSaved?"凭据已保存":"未登录")+'</span><span class="chevron">›</span></button>';
 html+='<div class="section-head"><h2>账号列表 <span style="color:var(--muted);font-weight:400">('+((state.accounts||[]).length)+')</span></h2></div>';
 if(!state.accounts||!state.accounts.length){html+='<div class="card empty"><div class="empty-icon">◎</div><strong>云端暂未读取到账号</strong><p>连接华为云后将自动读取现有函数配置。账号信息不会保存到本机。</p></div>'}
 else state.accounts.forEach(a=>{
  const gameStates=Object.values(a.gameTodayStatuses||{});
  const aggregate=gameStates.some(x=>x==="failed")?"今日签到失败":
    gameStates.some(x=>x==="success")?"今日签到成功":
    ((a.games||[]).length===0||gameStates.length>0&&gameStates.every(x=>x==="unconfigured"))?"未配置":
    a.todayStatus||"今日状态未知";
  const statusTone=aggregate==="今日签到成功"?"ok":aggregate==="今日签到失败"?"bad":aggregate==="未配置"?"muted":"warn";
  html+='<button class="account-card" data-account="'+esc(a.id)+'"><div class="account-avatar">'+esc(platformInitial(a.platform))+'</div><div class="account-main"><strong>'+esc(a.name)+'</strong><small>'+esc(a.platformLabel)+'</small></div><span class="account-status '+statusTone+'">'+esc(aggregate)+'</span><span class="chevron">›</span></button>'
 });
 html+='<button class="primary" data-action="add" '+(!state.canAddAccount?'disabled':'')+'>＋ 添加账号</button>';
 html+='<p class="hint" style="margin:12px 4px 0">账号与游戏配置以华为云为准。此设备不保存 HoYoLAB Cookie。</p>';
 return html
}
function renderDetail(a){
 if(!a)return renderHome();
 let html='<div class="detail-head"><button class="back-button" data-action="back">‹</button><div class="detail-title"><h1>'+esc(a.name)+'</h1><p>'+esc(a.platformLabel)+' · 账号详情</p></div></div>';
 if(a.platform==="hoyolab"||a.platform==="miyoushe_cn"){
  const pageCookieReady=!!(state.hoyoPageCookieReady&&state.hoyoPageCookieAccountId===a.id);
  const server=a.platform==="miyoushe_cn"?"cn":"os";
  const serverLabel=server==="cn"?"国服":"国际服";
  const selected=selectedHoyoGames(a);
  const options=hoyoGameOptions[server];
  const cloudEnabled=new Set((a.games||[]).map(g=>g.key));
  const gameStates=a.gameTodayStatuses||{};
  const gameRows=options.map(([key,label])=>{
   const enabled=cloudEnabled.has(key);
   const result=enabled?(gameStates[key]||"unknown"):"unconfigured";
   const resultText=result==="success"?"今日签到成功":result==="failed"?"失败":result==="unconfigured"?"未配置":"未确认";
   const tone=result==="success"?"success":result==="failed"?"failed":"neutral";
   return '<tr><td>'+esc(label)+'</td><td><span class="game-result '+tone+'">'+resultText+'</span></td></tr>'
  }).join("");
  html+='<div class="card"><div class="card-title">今日签到信息</div><div class="game-status-table-wrap"><table class="game-status-table"><thead><tr><th>游戏</th><th>今日状态</th></tr></thead><tbody>'+gameRows+'</tbody></table></div></div>';
  html+='<div class="card"><div class="card-title">更新上传</div>';
  html+='<div class="metric" style="margin-bottom:12px"><span>网页 Cookie 获取状态</span><strong style="color:'+(pageCookieReady?'var(--green)':'var(--amber)')+'">'+(pageCookieReady?'已获取 · '+serverLabel:'未获取')+'</strong></div>';
  html+='<button class="primary" data-action="hoyo-login">'+(a.platform==="miyoushe_cn"?"打开米游社登录页面":"打开 HoYoLAB 登录页面")+'</button>';
  html+='<button class="secondary game-picker-trigger" data-action="open-game-picker">选择要启用的游戏</button>';
  html+='<button class="primary" data-action="deploy-hoyo-account" '+(!pageCookieReady||!state.cloudConnected?'disabled':'')+'>更新云函数并上传</button>';
  html+='<p class="hint">默认勾选云端当前启用的游戏。上传时会同时更新游戏选择和本次网页 Cookie；其他云端配置保持不变。</p></div>';
 }else{
  const gameStates=a.gameTodayStatuses||{};
  const games=a.games||[];
  const rows=games.map(g=>{
   const key=g.key||g.id||g.label;
   const result=gameStates[key]||"unknown";
   const resultText=result==="success"?"今日签到成功":result==="failed"?"失败":result==="unconfigured"?"未配置":"未确认";
   const tone=result==="success"?"success":result==="failed"?"failed":"neutral";
   return '<tr><td>'+esc(g.label||key)+'</td><td><span class="game-result '+tone+'">'+resultText+'</span></td></tr>';
  }).join("");
  html+='<div class="card"><div class="card-title">今日签到信息</div><div class="game-status-table-wrap"><table class="game-status-table"><thead><tr><th>游戏</th><th>今日状态</th></tr></thead><tbody>'+ (rows||'<tr><td colspan="2" class="empty-cell">云端暂未配置签到游戏</td></tr>') +'</tbody></table></div></div>';
  html+='<div class="card"><div class="card-title">更新上传</div>';
  html+='<div class="metric" style="margin-bottom:12px"><span>森空岛 Token 获取状态</span><strong style="color:'+(a.tokenReady?'var(--green)':'var(--amber)')+'">'+(a.tokenReady?'已获取':'未获取')+'</strong></div>';
  html+='<button class="primary" data-action="skland-login">打开森空岛登录页面</button>';
  html+='<p class="hint">登录后会自动检测并加密保存 Token；签到游戏与状态来自云端配置。</p></div>';
 }
 if(a.platform==="skland"){
  const sklandMessage=state.sklandMessage||"暂无操作记录。点击上方“打开森空岛登录页面”后，加载状态和错误信息会显示在这里。";
  html+='<div class="skland-error-wrap"><div class="skland-error-text">'+esc(sklandMessage)+'</div><button class="skland-copy-button" data-action="copy-skland-error">复制消息</button></div>';
 }
 return html
}
function renderCloud(){
 let html=pageTitle("华为云","管理华为云连接与凭据。");
 html+='<div class="card"><div class="card-title">云端凭据</div><div class="field-row"><label>Region</label><select id="cloud-region"><option value="cn-east-3">华东-上海一</option><option value="cn-east-2">华东-上海二</option><option value="cn-north-4">华北-北京四</option><option value="cn-north-1">华北-北京一</option><option value="cn-south-1">华南-广州</option><option value="ap-southeast-1">中国-香港</option><option value="ap-southeast-3">亚太-新加坡</option></select></div>';
 html+='<div class="field-row"><label>AK</label><input id="cloud-ak" autocomplete="off" autocapitalize="off" placeholder="输入华为云 Access Key"></div>';
 html+='<div class="field-row"><label>SK</label><input id="cloud-sk" type="password" autocomplete="off" placeholder="输入华为云 Secret Key"></div>';
 html+='<p class="hint">AK/SK 登录成功后沿用现有加密保管箱。HoYoLAB Cookie 和账号配置不会写入本地。</p><div class="cloud-login-row"><button class="primary" data-action="cloud-login">登录华为云</button><div class="cloud-connection-inline"><span class="connection-dot '+(state.cloudConnected?'connected':'')+'"></span><span>'+(state.cloudConnected?"已连接":state.cloudConnectionLabel||"未连接")+'</span></div></div></div>';
 return html
}
function renderMessages(){
 const content=messageTab==="skland"?(state.sklandMessage||"暂无操作记录"):(state.hoyolabMessage||"暂无操作记录");
 return pageTitle("运行消息","不同服务的消息相互独立。")+'<div class="tabs"><button class="tab '+(messageTab==="hoyolab"?"active":"")+'" data-message="hoyolab">HoYoLAB</button><button class="tab '+(messageTab==="skland"?"active":"")+'" data-message="skland">森空岛</button></div><div class="message-box">'+esc(content)+'</div><button class="secondary" data-action="message-refresh">刷新消息</button>'
}
function render(){
 nav();
 const a=account();
 if(selectedId&&a)view.innerHTML=renderDetail(a);
 else if(page==="cloud")view.innerHTML=renderCloud();
 else if(page==="messages")view.innerHTML=renderMessages();
 else view.innerHTML=renderHome();
 if(page==="cloud"){
  if(!cloudVaultLoaded){try{const saved=native("getSavedCloudCredentials");if(saved){const pair=JSON.parse(saved);cloudDraft.ak=pair.ak||"";cloudDraft.sk=pair.sk||"";}cloudVaultLoaded=true;}catch(e){}}
  const r=$("#cloud-region");if(r)r.value=cloudDraft.region||state.region||"cn-east-3";
  const ak=$("#cloud-ak");if(ak)ak.value=cloudDraft.ak;
  const sk=$("#cloud-sk");if(sk)sk.value=cloudDraft.sk;
 }
}
function showModal(){
 $("#modal-root").innerHTML='<div class="modal-backdrop" data-action="modal-close"><div class="modal"><h3>添加账号</h3><p>选择需要添加的平台。账号数据会在读取云端后显示。</p><button class="choice" data-platform="hoyolab"><span>🌐</span><span><b>HoYoLAB（国际服）</b><small>国际服游戏账号</small></span></button><button class="choice" data-platform="miyoushe_cn"><span>🇨🇳</span><span><b>米游社（国服）</b><small>国服游戏账号</small></span></button><button class="choice" data-platform="skland"><span>🛰</span><span><b>森空岛</b><small>明日方舟 / 终末地</small></span></button><button class="secondary" data-action="modal-close">取消</button></div></div>'
}
document.addEventListener("click",e=>{
 const accountButton=e.target.closest("[data-account]");if(accountButton){selectedId=accountButton.dataset.account;page="home";render();return}
 const platform=e.target.closest("[data-platform]");if(platform){const p=platform.dataset.platform;$("#modal-root").innerHTML="";native("addAccount",p);return}
 const msg=e.target.closest("[data-message]");if(msg){messageTab=msg.dataset.message;render();return}
 const pageButton=e.target.closest("[data-page]");if(pageButton){selectedId=null;page=pageButton.dataset.page;render();return}
 const action=e.target.closest("[data-action]")?.dataset.action;if(!action)return;
 
 if(action==="add"){if(state.canAddAccount)showModal();else toast("请先成功读取云端配置")}
 if(action==="modal-close"&&e.target.classList.contains("modal-backdrop"))$("#modal-root").innerHTML="";
 if(action==="modal-close"&&e.target.closest(".modal"))$("#modal-root").innerHTML="";
 if(action==="game-modal-close"&&(e.target.classList.contains("game-modal-backdrop")||e.target.closest(".modal-x")))closeGamePicker();
 if(action==="game-modal-cancel")closeGamePicker();
 if(action==="game-modal-save"){
  const a=account();if(a){
   const chosen=new Set(Array.from(document.querySelectorAll("input[data-modal-game]:checked")).map(x=>x.dataset.modalGame));
   gameSelections[a.id]=chosen;closeGamePicker();render();toast("游戏选择已保存");
  }
 }
 if(action==="back"){selectedId=null;native("backToHome");render()}
 if(action==="hoyo-login"){const a=account();if(a)native("openHoyoLogin",a.id)}
 if(action==="open-game-picker"){showGamePicker()}
 if(action==="deploy-hoyo-account"){const a=account();if(a)native("deployHoyoAccount",a.id,JSON.stringify(Array.from(selectedHoyoGames(a))));toast("正在合并云端配置并上传")}
 if(action==="skland-login"){const a=account();if(a)native("openSklandLogin",a.id)}
 if(action==="copy-skland-error"){native("copyText",state.sklandMessage||"暂无操作记录")}
 if(action==="cloud-login"){
  cloudDraft.region=$("#cloud-region").value;cloudDraft.ak=$("#cloud-ak").value;cloudDraft.sk=$("#cloud-sk").value;
  native("cloudLogin",cloudDraft.region,cloudDraft.ak,cloudDraft.sk);toast("正在验证华为云凭据")
 }
 
 if(action==="message-refresh"){readState();toast("消息已刷新")}
});
document.addEventListener("focusout",()=>setTimeout(()=>{if(!(document.activeElement&&document.activeElement.matches("input,select,textarea")))render()},0));
function showGamePicker(){
 const a=account();if(!a)return;
 const server=a.platform==="miyoushe_cn"?"cn":"os";
 const options=hoyoGameOptions[server];
 const selected=selectedHoyoGames(a);
 const items=options.map(([key,label])=>'<label class="game-select-option"><input type="checkbox" data-modal-game="'+esc(key)+'" '+(selected.has(key)?'checked':'')+'><span>'+esc(label)+'</span></label>').join("");
 $("#modal-root").innerHTML='<div class="modal-backdrop game-modal-backdrop" data-action="game-modal-close"><div class="modal game-picker-modal"><div class="modal-topline"><div><h3>选择要启用的游戏</h3><p>勾选需要参与自动签到的游戏。</p></div><button class="modal-x" data-action="game-modal-close" aria-label="关闭">×</button></div><div class="game-select-list game-modal-list">'+items+'</div><div class="modal-actions"><button class="secondary" data-action="game-modal-cancel">取消</button><button class="primary" data-action="game-modal-save">保存选择</button></div></div></div>';
}
function closeGamePicker(){const root=$("#modal-root");if(root)root.innerHTML=""}
document.addEventListener("change",e=>{
 if(e.target.matches("input[data-modal-game]"))e.target.dataset.changed="1";
});
document.addEventListener("input",e=>{
 if(e.target.id==="cloud-region")cloudDraft.region=e.target.value;
 if(e.target.id==="cloud-ak")cloudDraft.ak=e.target.value;
 if(e.target.id==="cloud-sk")cloudDraft.sk=e.target.value;
});
window.nativeBack=()=>{if(selectedId){selectedId=null;native("backToHome");render()}else if(page!=="home"){page="home";render()}else{toast("再按一次可退出应用")}};
window.addEventListener("load",()=>{readState();render()});
setInterval(()=>{try{const raw=native("getState");if(raw)applyState(JSON.parse(raw))}catch(e){}},1800);
})();