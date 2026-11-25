(function(){
"use strict";
const $=s=>document.querySelector(s);
const view=$("#view");
let state={accounts:[],syncState:"unknown",canAddAccount:false};
let page="home",selectedId=null,messageTab="hoyolab",cloudDraft={region:"cn-east-3",ak:"",sk:""},cloudVaultLoaded=false;
const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const account=()=>state.accounts.find(x=>x.id===selectedId);
const platformInitial=p=>p==="skland"?"森":p==="miyoushe_cn"?"米":"H";
const statusClass=s=>/成功|已连接|已获取|同步/.test(s)?"ok":/失败|未获取/.test(s)?"bad":"warn";
function toast(msg){const root=$("#toast-root");root.innerHTML='<div class="toast">'+esc(msg)+'</div>';const t=root.firstElementChild;requestAnimationFrame(()=>t.classList.add("show"));setTimeout(()=>t.classList.remove("show"),2200)}
function native(name,...args){try{if(window.Android&&typeof Android[name]==="function")return Android[name](...args)}catch(e){toast("操作失败："+e.message)}}
function refresh(){try{const raw=native("getState");if(raw)applyState(JSON.parse(raw))}catch(e){console.warn(e)}}
function applyState(next){if(!next)return;state=next;if(!cloudDraft.region||cloudDraft.region==="cn-east-3")cloudDraft.region=state.region||"cn-east-3";if(document.activeElement&&document.activeElement.matches("input,select,textarea"))return;render()}
window.onNativeState=raw=>{try{applyState(typeof raw==="string"?JSON.parse(raw):raw)}catch(e){console.error(e)}};
function pageTitle(title,sub){return '<div class="page-heading"><div><div class="eyebrow">HOYO CHECK-IN</div><h1>'+title+'</h1><p>'+sub+'</p></div></div>'}
function nav(){document.querySelectorAll(".nav-item").forEach(x=>x.classList.toggle("active",x.dataset.page===page))}
function renderHome(){
 const sync=state.syncLabel||"等待连接华为云";
 let html=pageTitle("账号总览","每个账号独立显示今日签到结果。");
 html+='<div class="card status-card"><div class="status-icon">☁</div><div class="status-copy"><strong>云端配置</strong><span>'+esc(sync)+'</span></div><span class="pill '+(state.syncState==="synced"?"ok":state.syncState==="failed"?"bad":"warn")+'">'+(state.cloudConnected?"已连接":state.cloudCredentialsSaved?"凭据已保存":"未连接")+'</span></div>';
 html+='<div class="section-head"><h2>账号列表 <span style="color:var(--muted);font-weight:400">('+((state.accounts||[]).length)+')</span></h2><button class="text-button" data-action="refresh">刷新状态 ↻</button></div>';
 if(!state.accounts||!state.accounts.length){html+='<div class="card empty"><div class="empty-icon">◎</div><strong>云端暂未读取到账号</strong><p>先连接华为云并读取现有函数配置。账号信息不会保存到本机。</p><button class="secondary" data-action="sync">重新读取云端</button></div>'}
 else state.accounts.forEach(a=>{html+='<button class="account-card" data-account="'+esc(a.id)+'"><div class="account-avatar">'+esc(platformInitial(a.platform))+'</div><div class="account-main"><strong>'+esc(a.name)+'</strong><small>'+esc(a.platformLabel)+'</small></div><span class="account-status">'+esc(a.todayStatus||"今日状态未知")+'</span><span class="chevron">›</span></button>'});
 html+='<button class="primary" data-action="add" '+(!state.canAddAccount?'disabled':'')+'>＋ 添加账号</button>';
 html+='<p class="hint" style="margin:12px 4px 0">账号与游戏配置以华为云为准。此设备不保存 HoYoLAB Cookie。</p>';
 return html
}
function renderDetail(a){
 if(!a)return renderHome();
 let html='<div class="detail-head"><button class="back-button" data-action="back">‹</button><div class="detail-title"><h1>'+esc(a.name)+'</h1><p>'+esc(a.platformLabel)+' · 账号详情</p></div></div>';
 if(a.platform==="hoyolab"||a.platform==="miyoushe_cn"){
  const pageCookieReady=!!(state.hoyoPageCookieReady&&state.hoyoPageCookieAccountId===a.id);
  const serverLabel=state.hoyoPageCookieServer==="cn"?"国服":state.hoyoPageCookieServer==="os"?"国际服":"";
  html+='<div class="card"><div class="card-title">云端信息</div>';
  html+='<div class="metric" style="margin-bottom:12px"><span>账号游戏区</span><strong>'+(a.platform==="miyoushe_cn"?"国服 · 米游社":"国际服 · HoYoLAB")+'</strong></div>';
  html+='<div class="game-list">';
  if(a.games&&a.games.length)a.games.forEach(g=>html+='<span class="game-chip enabled">'+esc(g.label)+'</span>');
  else html+='<span class="hint">云端配置未识别到已启用的游戏</span>';
  html+='</div><p class="hint">以上账号与游戏信息来自华为云函数配置；更新 Cookie 不会改动其他云端设置。</p>';
  html+='<div class="metric"><span>今日签到</span><strong>'+esc(a.todayStatus||"状态未知")+'</strong></div></div>';
  html+='<div class="card"><div class="card-title">更新上传</div>';
  html+='<div class="metric" style="margin-bottom:12px"><span>网页 Cookie 获取状态</span><strong style="color:'+(pageCookieReady?'var(--green)':'var(--amber)')+'">'+(pageCookieReady?'已获取'+(serverLabel?' · '+serverLabel:''):'未获取')+'</strong></div>';
  html+='<button class="primary" data-action="hoyo-login">'+(a.platform==="miyoushe_cn"?"打开米游社登录页面":"打开 HoYoLAB 登录页面")+'</button>';
  html+='<button class="primary" data-action="deploy-hoyo-account" '+(!pageCookieReady||!state.cloudConnected?'disabled':'')+'>更新云函数并上传</button>';
  html+='<p class="hint">上传时使用 APK 内置函数 ZIP，并将本次网页获取的 Cookie 写入云端原配置对应位置；其他云端配置保持不变。请先连接华为云。</p></div>';
 }else{
  html+='<div class="card"><div class="card-title">凭据获取状态</div>';
  html+='<div class="metric"><span>森空岛 Token</span><strong style="color:'+(a.tokenReady?'var(--green)':'var(--amber)')+'">'+(a.tokenReady?'已获取':'未获取')+'</strong></div>';
  html+='<button class="primary" data-action="skland-login">打开森空岛登录页面</button></div>';
  html+='<div class="card"><div class="card-title">签到游戏（云端只读）</div><div class="game-list">';
  if(a.games&&a.games.length)a.games.forEach(g=>html+='<span class="game-chip enabled">'+esc(g.label)+'</span>');
  else html+='<span class="hint">云端配置未识别到已启用的游戏</span>';
  html+='</div><p class="hint">游戏选择直接来自云端配置，不在本机保存或修改。</p></div>';
  html+='<div class="card"><div class="card-title">今日签到</div><span class="pill '+statusClass(a.todayStatus)+'">'+esc(a.todayStatus||"状态未知")+'</span></div>';
 }
 if(a.platform==="skland"){
  const sklandMessage=state.sklandMessage||"暂无操作记录。点击上方“打开森空岛登录页面”后，加载状态和错误信息会显示在这里。";
  html+='<div class="skland-error-wrap"><div class="skland-error-text">'+esc(sklandMessage)+'</div><button class="skland-copy-button" data-action="copy-skland-error">复制消息</button></div>';
 }
 return html
}
function renderCloud(){
 let html=pageTitle("华为云","连接 FunctionGraph，读取配置、更新函数并查看运行日志。");
 html+='<div class="card status-card"><div class="status-icon">☁</div><div class="status-copy"><strong>连接状态</strong><span>'+esc(state.syncLabel||"等待连接")+'</span></div><span class="pill '+(state.cloudConnected?"ok":"warn")+'">'+(state.cloudConnected?"已连接":"未连接")+'</span></div>';
 html+='<div class="card"><div class="card-title">云端凭据</div><div class="field-row"><label>Region</label><select id="cloud-region"><option value="cn-east-3">华东-上海一</option><option value="cn-east-2">华东-上海二</option><option value="cn-north-4">华北-北京四</option><option value="cn-north-1">华北-北京一</option><option value="cn-south-1">华南-广州</option><option value="ap-southeast-1">中国-香港</option><option value="ap-southeast-3">亚太-新加坡</option></select></div>';
 html+='<div class="field-row"><label>AK</label><input id="cloud-ak" autocomplete="off" autocapitalize="off" placeholder="输入华为云 Access Key"></div>';
 html+='<div class="field-row"><label>SK</label><input id="cloud-sk" type="password" autocomplete="off" placeholder="输入华为云 Secret Key"></div>';
 html+='<p class="hint">AK/SK 登录成功后沿用现有加密保管箱。HoYoLAB Cookie 和账号配置不会写入本地。</p><button class="primary" data-action="cloud-login">登录并连接华为云</button><button class="secondary" data-action="sync">重新读取云端配置</button></div>';
 html+='<div class="card"><div class="card-title">云函数维护</div><p class="hint">HoYoLAB 请进入对应账号详情页，在“更新上传”板块获取网页 Cookie 后更新；森空岛仍可在此更新。部署前会先测试运行，成功后才检查定时触发器。</p><button class="secondary" data-action="deploy-skland">更新森空岛云函数</button></div>';
 html+='<div class="card"><div class="card-title">最近一周函数运行日志</div><p class="hint">'+esc(state.logsStatus||"读取 HoYoLAB 与森空岛两个服务最近 7 天的运行输出。")+'</p><button class="secondary" data-action="logs">读取运行日志</button><div id="logs-output" class="message-box" style="margin-top:12px">'+esc(state.logsText||"点击上方按钮读取最近一周日志。")+'</div></div>';
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
 if(action==="refresh"){native("refresh");refresh();toast("正在刷新");}
 if(action==="sync"){native("syncCloud");toast("正在读取云端配置");}
 if(action==="add"){if(state.canAddAccount)showModal();else toast("请先成功读取云端配置")}
 if(action==="modal-close"&&e.target.classList.contains("modal-backdrop"))$("#modal-root").innerHTML="";
 if(action==="modal-close"&&e.target.closest(".modal"))$("#modal-root").innerHTML="";
 if(action==="back"){selectedId=null;native("backToHome");render()}
 if(action==="hoyo-login"){const a=account();if(a)native("openHoyoLogin",a.id)}
 if(action==="deploy-hoyo-account"){const a=account();if(a)native("deployHoyoAccount",a.id);toast("正在合并云端配置并上传")}
 if(action==="skland-login"){const a=account();if(a)native("openSklandLogin",a.id)}
 if(action==="copy-skland-error"){native("copyText",state.sklandMessage||"暂无操作记录")}
 if(action==="cloud-login"){
  cloudDraft.region=$("#cloud-region").value;cloudDraft.ak=$("#cloud-ak").value;cloudDraft.sk=$("#cloud-sk").value;
  native("cloudLogin",cloudDraft.region,cloudDraft.ak,cloudDraft.sk);toast("正在验证华为云凭据")
 }
 if(action==="deploy-hoyo"){native("deploy","hoyolab");toast("正在更新 HoYoLAB 函数")}
 if(action==="deploy-skland"){native("deploy","skland");toast("正在更新森空岛函数")}
 if(action==="logs"){native("fetchLogs");toast("正在读取最近一周日志")}
 if(action==="message-refresh"){refresh();toast("消息已刷新")}
});
document.addEventListener("focusout",()=>setTimeout(()=>{if(!(document.activeElement&&document.activeElement.matches("input,select,textarea")))render()},0));
document.addEventListener("input",e=>{
 if(e.target.id==="cloud-region")cloudDraft.region=e.target.value;
 if(e.target.id==="cloud-ak")cloudDraft.ak=e.target.value;
 if(e.target.id==="cloud-sk")cloudDraft.sk=e.target.value;
});
window.nativeBack=()=>{if(selectedId){selectedId=null;native("backToHome");render()}else if(page!=="home"){page="home";render()}else{toast("再按一次可退出应用")}};
window.addEventListener("load",()=>{refresh();render()});
setInterval(()=>{try{const raw=native("getState");if(raw)applyState(JSON.parse(raw))}catch(e){}},1800);
})();