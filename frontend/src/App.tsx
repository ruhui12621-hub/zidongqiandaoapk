import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowLeft, CalendarCheck, Check, CheckCircle2, ChevronRight, Cloud,
  Copy, Gamepad2, LoaderCircle, LogIn, Plus, RefreshCw,
  Settings2, Users, X
} from "lucide-react";

type AnyState = any;
declare global {
  interface Window {
    Android?: Record<string, (...args: string[]) => any>;
    onNativeState?: (raw: string | AnyState) => void;
    nativeBack?: () => void;
  }
}

const callNative = (name: string, ...args: string[]) => {
  try {
    const bridge = window.Android;
    if (bridge && typeof bridge[name] === "function") return bridge[name](...args);
  } catch (error: any) {
    console.warn("Native bridge call failed", name, error);
  }
  return undefined;
};
const safe = (value: any) => String(value ?? "");
const statusTone = (value: string) => /成功|已连接|已获取|已同步|已完成/.test(value) ? "good" : /失败|错误|未获取|无法获取/.test(value) ? "bad" : "muted";
const platformLabel = (platform: string) => platform === "miyoushe_cn" ? "米游社国服" : platform === "hoyolab" ? "HoYoLAB 国际服" : platform === "skland" ? "森空岛" : "未知平台";
const accountSummary = (platform: string, games: AnyState[], statuses: Record<string, string>, sourceStatus: string) => {
  if (!games.length) return "未启用游戏";
  if (/无法获取|查询失败|读取失败/.test(sourceStatus)) return "无法获取结果";
  const results = games.map(game => statuses[game.key || game.id] || "unknown");
  const success = results.filter(value => value === "success").length;
  const failed = results.filter(value => value === "failed").length;
  const pending = results.length - success - failed;
  if (success === results.length) return "全部签到成功";
  if (failed === results.length) return "全部签到失败";
  if (failed && success) return "部分游戏失败";
  if (failed && pending) return "有失败项，其他待确认";
  if (success && pending) return "部分完成，其他待确认";
  if (results.every(value => value === "unconfigured")) return "未配置";
  return "签到结果待确认";
};
const gameStatusText = (value: string) => value === "success" ? "签到成功" : value === "failed" ? "签到失败" : value === "unconfigured" ? "未配置" : "结果待确认";
const gameTone = (value: string) => value === "success" ? "good" : value === "failed" ? "bad" : "muted";

export default function App() {
  const [state, setState] = useState<AnyState>({ accounts: [], syncState: "unknown", canAddAccount: false });
  const lastStateRef = useRef("");
  const [page, setPage] = useState("home");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedTodayId, setSelectedTodayId] = useState<string | null>(null);
  const [modal, setModal] = useState<"add" | null>(null);
  const [cloudDraft, setCloudDraft] = useState({ region: "cn-east-3", ak: "", sk: "" });
  const [cloudLoaded, setCloudLoaded] = useState(false);
  const [toast, setToast] = useState("");
  const [csvPlatform, setCsvPlatform] = useState("hoyolab");
  const [csvDrafts, setCsvDrafts] = useState<AnyState[]>([]);
  const [csvDirtyIds, setCsvDirtyIds] = useState<Set<string>>(new Set());
  const accountSnapshot = JSON.stringify((state.accounts || []).map((row: AnyState) => ({ id: row.id, platform: row.platform, name: row.name, userId: row.userId, games: row.games, enabled: row.enabled, remark: row.remark, cookieReady: row.cookieReady, tokenReady: row.tokenReady, cloudEligible: row.cloudEligible, cloudBacked: row.cloudBacked })));

  useEffect(() => {
    setCsvDrafts(old => {
      const previous = new Map<string, AnyState>(old.map((row: AnyState) => [row.id, row] as [string, AnyState]));
      return (state.accounts || []).map((row: AnyState) => {
        const draft = previous.get(row.id);
        if (!draft || !csvDirtyIds.has(row.id)) return row;
        const credentialJustArrived = (row.cookieReady && !draft.cookieReady) || (row.tokenReady && !draft.tokenReady);
        return { ...row, name: draft.name, games: draft.games, enabled: credentialJustArrived ? row.enabled : draft.enabled, remark: draft.remark };
      });
    });
  }, [accountSnapshot]);

  const account = useMemo(() => (state.accounts || []).find((item: AnyState) => item.id === selectedId), [state.accounts, selectedId]);

  const notify = useCallback((message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(""), 2300);
  }, []);

  const applyNativeState = useCallback((next: AnyState) => {
    if (!next) return;
    const snapshot = JSON.stringify(next);
    if (snapshot === lastStateRef.current) return;
    lastStateRef.current = snapshot;
    setState(next);
    if (Object.prototype.hasOwnProperty.call(next, "selectedAccountId")) {
      setSelectedId(next.selectedAccountId || null);
    }
    setCloudDraft(old => ({...old, region: old.region === "cn-east-3" ? (next.region || old.region) : old.region}));
  }, []);

  const readState = useCallback(() => {
    try {
      const raw = callNative("getState");
      if (raw) applyNativeState(JSON.parse(raw));
    } catch (error) { console.warn(error); }
  }, [applyNativeState]);

  useEffect(() => {
    // Native state is pushed through onNativeState whenever it changes.
    // Read once at startup; avoid synchronous JavaScript-bridge polling,
    // which can block WebView interactions while AccountStore is being read.
    window.onNativeState = raw => {
      try { applyNativeState(typeof raw === "string" ? JSON.parse(raw) : raw); }
      catch (error) { console.error(error); }
    };
    readState();
    return () => {
      window.onNativeState = undefined;
    };
  }, [readState, applyNativeState]);

  useEffect(() => {
    if (page !== "cloud" || cloudLoaded) return;
    try {
      const raw = callNative("getSavedCloudCredentials");
      if (raw) {
        const saved = JSON.parse(raw);
        setCloudDraft(old => ({...old, ak:saved.ak || "", sk:saved.sk || ""}));
      }
    } catch {}
    setCloudLoaded(true);
  }, [page, cloudLoaded]);

  const goHome = () => {
    setPage("home"); setSelectedId(null); 
    callNative("backToHome");
  };

  const closeModal = () => setModal(null);
  const goBack = () => {
    if (modal) { closeModal(); return; }
    if (selectedTodayId) { setSelectedTodayId(null); setPage("home"); return; }
    if (selectedId) { setSelectedId(null); setPage("home"); callNative("backToHome"); return; }
    if (page !== "home") { setPage("home"); return; }
    notify("再按一次可退出应用");
  };
  useEffect(() => {
    window.nativeBack = goBack;
    return () => { window.nativeBack = undefined; };
  });

  const copyText = (value: string, label: string) => {
    if (!value.trim()) { notify("没有可复制的内容"); return; }
    callNative("copyText", value);
    notify(label);
  };

  const addAccount = (platform: string) => {
    setModal(null);
    callNative("addAccount", platform);
  };

  const openLogin = () => {
    if (account) callNative("openLogin", account.id);
  };

  const submitCloudLogin = () => {
    if (!cloudDraft.ak.trim() || !cloudDraft.sk.trim()) { notify("请填写 AK 和 SK"); return; }
    callNative("cloudLogin", cloudDraft.region, cloudDraft.ak, cloudDraft.sk);
    notify("已提交连接请求，请查看连接状态");
  };

  const title = (heading: string, subtitle: string) => (
    <div className="page-heading">
      <h1>{heading}</h1>
      <p>{subtitle}</p>
    </div>
  );

  const backHeader = (heading: string, subtitle: string) => (
    <div className="back-heading">
      <button className="icon-button" onClick={goBack} aria-label="返回"><ArrowLeft size={19}/></button>
      <div className="back-heading-copy"><h1>{heading}</h1><p>{subtitle}</p></div>
    </div>
  );

  const getCsvGameOptions = (platform: string) => platform === "skland"
    ? state.gameCatalog?.skland || []
    : platform === "miyoushe_cn"
      ? state.gameCatalog?.hoyolab_cn || []
      : state.gameCatalog?.hoyolab_os || [];

  const updateCsvRow = (id: string, patch: AnyState) => {
    setCsvDirtyIds(old => new Set(old).add(id));
    setCsvDrafts(old => old.map(row => row.id === id ? { ...row, ...patch } : row));
  };

  const updateCsvGame = (row: AnyState, gameKey: string, checked: boolean) => {
    const current = (row.games || []).map((item: AnyState) => item.key || item);
    const next = checked ? [...new Set([...current, gameKey])] : current.filter((key: string) => key !== gameKey);
    const options = getCsvGameOptions(row.platform);
    updateCsvRow(row.id, { games: next.map(key => ({
      key,
      label: options.find((item: AnyState) => item.key === key)?.label || key
    })) });
  };

  const saveCsv = () => {
    const rows = csvPlatform === "skland"
      ? csvDrafts.filter((row: AnyState) => row.platform === "skland")
      : csvDrafts.filter((row: AnyState) => row.platform === "hoyolab" || row.platform === "miyoushe_cn");
    const visibleRows = rows;
    if (!visibleRows.length) { notify("当前平台没有账号行"); return; }
    if (!state.cloudConnected) { notify("请先连接华为云"); return; }
    const payload = rows.map((row: AnyState) => ({
      id: row.id,
      name: row.name || "",
      games: (row.games || []).map((game: AnyState) => game.key || game),
      enabled: !!row.enabled,
      remark: row.remark || ""
    }));
    setCsvDirtyIds(old => {
      const next = new Set(old);
      rows.forEach((row: AnyState) => next.delete(row.id));
      return next;
    });
    callNative("savePlatformAccounts", csvPlatform, JSON.stringify(payload), selectedId || "");
    notify("正在保存 CSV 并更新平台云函数");
  };

  const renderPlatformAccounts = (platform: string) => {
    const accounts = state.accounts || [];
    const liveRows = platform === "skland"
      ? accounts.filter((item: AnyState) => item.platform === "skland")
      : accounts.filter((item: AnyState) => item.platform === "hoyolab" || item.platform === "miyoushe_cn");
    const titleText = platform === "skland" ? "森空岛" : "HoYoLAB 与米游社";
    const description = platform === "skland" ? "明日方舟与终末地账号" : "国际服与国服账号 · 共用一份 CSV";
    return <section className="surface-card platform-account-panel">
      <div className="platform-section-heading">
        <div className="platform-section-copy">
          <h2>{titleText}</h2>
          <p>{description}</p>
        </div>
        <span className="status-pill muted">{liveRows.length} 个账号</span>
      </div>
      {liveRows.length ? <div className="account-pair-list">
        {liveRows.map((row: AnyState) => {
          const gameNames = (row.games || []).map((game: AnyState) => game.label || game.key || game).filter(Boolean);
          const credentialReady = row.platform === "skland" ? row.tokenReady : row.cookieReady;
          const credentialName = row.platform === "skland" ? "Token" : "Cookie";
          return <div className="account-overview-row" key={row.id}>
            <button className="account-overview-main" onClick={() => {
              setCsvPlatform(row.platform === "skland" ? "skland" : "hoyolab");
              setSelectedTodayId(null);
              setSelectedId(row.id);
              callNative("openAccount", row.id);
            }}>
              <span className="account-overview-title">
                <strong>{row.name || "未命名账号"}</strong>
                <span className={"account-enabled " + (row.enabled ? "is-enabled" : "is-disabled")}>{row.enabled ? "运行中" : "已停用"}</span>
              </span>
              <span className="account-overview-meta">{platformLabel(row.platform)}{gameNames.length ? " · " + gameNames.join("、") : " · 未启用游戏"}</span>
              <span className="account-overview-meta">
                <span className={"credential-mini " + (credentialReady ? "ready" : "missing")}>{credentialName}{credentialReady ? "已保存" : "未保存"}</span>
                {row.remark ? <span className="account-remark">{row.remark}</span> : null}
              </span>
            </button>
            <button className={"account-overview-status " + statusTone(row.todayStatus || "")} onClick={() => {
              setSelectedId(null);
              setSelectedTodayId(row.id);
              window.scrollTo({ top: 0, left: 0, behavior: "instant" as ScrollBehavior });
            }}>
              <span className="account-status-label">{row.todayStatus || "状态未知"}</span>
              <small>今日签到</small>
              <ChevronRight size={15}/>
            </button>
          </div>;
        })}
      </div> : <div className="empty-state compact-empty">
        <strong>{state.syncState === "syncing" ? "正在读取云端账号" : state.syncState === "failed" ? "读取云端账号失败" : "暂无账号"}</strong>
        <p>{state.syncState === "failed" ? (state.syncLabel || "请检查华为云连接和函数配置。") : "添加账号后，会直接显示在此处。"}</p>
      </div>}
      <button className="secondary-button full-button platform-add-button" onClick={() => {setCsvPlatform(platform);state.canAddAccount ? setModal("add") : notify("请先成功读取云端配置");}} disabled={!state.canAddAccount}><Plus size={16}/> 添加账号</button>
    </section>;
  };
  const renderHome = () => {
    const allAccounts = state.accounts || [];
    const enabledAccounts = allAccounts.filter((item: AnyState) => item.enabled).length;
    return <>
      {title("账号与签到", "账号状态与今日结果一览")}
      <div className="cloud-status-row">
        <span className="banner-icon"><Cloud size={19}/></span>
        <span className="banner-copy"><strong>华为云</strong><small>{state.syncLabel || "等待连接华为云"}</small></span>
        <span className={"status-pill " + (state.cloudConnected ? "good" : "muted")}>{state.cloudConnected ? "已连接" : state.cloudCredentialsSaved ? "凭据已保存" : "未连接"}</span>
      </div>
      <div className="home-overview-strip">
        <div><strong>{allAccounts.length}</strong><span>全部账号</span></div>
        <div><strong>{enabledAccounts}</strong><span>启用中</span></div>
        <div><strong>{allAccounts.length - enabledAccounts}</strong><span>已停用</span></div>
        <button className="overview-refresh" onClick={() => {callNative("refreshTodayStatuses");notify("正在读取今日云函数日志");}} disabled={state.todayQuerying}>
          {state.todayQuerying ? <LoaderCircle className="spin" size={16}/> : <RefreshCw size={16}/>}<span>刷新结果</span>
        </button>
      </div>
      {state.todaySummary ? <p className="home-today-summary">{state.todayQuerying ? "正在读取并分析今日云函数日志…" : state.todaySummary}</p> : null}
      {renderPlatformAccounts("hoyolab")}
      {renderPlatformAccounts("skland")}
    </>;
  };
  const renderTodayDetail = () => {
    const todayAccount = (state.accounts || []).find((item: AnyState) => item.id === selectedTodayId);
    if (!todayAccount) return renderHome();
    const details = todayAccount.todayDetails || {};
    const gameDetails = details.games || {};
    const logs = Array.isArray(details.allRawMessages) ? details.allRawMessages.join(String.fromCharCode(10)) : safe(details.allRawMessages);
    const games = (todayAccount.games || []).map((game: AnyState) => {
      const key = game.key || game.id;
      const catalog = getCsvGameOptions(todayAccount.platform);
      return { key, label: game.label || catalog.find((item: AnyState) => item.key === key)?.label || key };
    });
    return <>
      {backHeader(todayAccount.name || "未命名账号", "今日签到详情")}
      <section className="surface-card">
        <div className="card-heading">
          <span>今日签到结果</span>
          <span className={"result-label " + statusTone(todayAccount.todayStatus || "")}>{todayAccount.todayStatus || "今日状态未知"}</span>
        </div>
        <div className="info-line"><span>账号</span><strong>{todayAccount.name || "未命名账号"}</strong></div>
        <div className="info-line"><span>平台</span><strong>{platformLabel(todayAccount.platform)}</strong></div>
        <details className="log-source-details"><summary>日志来源</summary><div className="info-line"><span>日志组</span><strong>{details.logGroupName || "未获取"}</strong></div><div className="info-line"><span>日志流</span><strong>{details.logStreamName || "未获取"}</strong></div></details>
        <div className="card-heading detail-log-heading"><span>各游戏签到情况</span></div>
        {games.length ? <div className="result-table"><table>
          <thead><tr><th>游戏</th><th>状态</th><th>日志判断依据</th></tr></thead>
          <tbody>{games.map((game: AnyState) => {
            const key = game.key;
            const status = todayAccount.gameTodayStatuses?.[key] || "unknown";
            return <tr key={key}><td>{game.label}</td><td><span className={"result-label " + gameTone(status)}>{gameStatusText(status)}</span></td><td>{gameDetails[key]?.reason || "没有获取到明确的游戏级日志结果。"}</td></tr>;
          })}</tbody>
        </table></div> : <p className="empty-inline">该账号尚未启用任何游戏签到。</p>}
        <div className="card-heading detail-log-heading">
          <span>云函数原始日志</span>
          <button className="text-action" onClick={() => copyText(logs, "签到日志已复制")}><Copy size={14}/> 复制</button>
        </div>
        <details className="raw-log-details"><summary>查看原始日志</summary><pre className="log-view">{logs || details.reason || "没有读取到匹配此账号的日志。"}</pre></details>
        <button className="secondary-button full-button" onClick={() => {callNative("refreshTodayStatuses");notify("正在刷新今日签到结果");}} disabled={state.todayQuerying}>
          {state.todayQuerying ? <LoaderCircle className="spin" size={16}/> : <RefreshCw size={16}/>} 刷新今日签到
        </button>
      </section>
    </>;
  };
  const renderDetail = () => {
    if (!account) return renderHome();
    const draft = csvDrafts.find((row: AnyState) => row.id === account.id) || account;
    const isHoyo = account.platform === "hoyolab" || account.platform === "miyoushe_cn";
    // This is process-session state for either platform, never inferred from saved credentials.
    const pageCredentialReady = !!account.sessionCredentialAcquired;
    const credentialLabel = isHoyo ? "Cookie" : "Token";
    // Shared runtime-only indicator is supplied by the native process state.
    const options = getCsvGameOptions(account.platform);
    const selectedGames = new Set((draft.games || []).map((game: AnyState) => game.key || game));
    const stages = account.cloudUpdateStages || [];
    const visibleStages = stages.filter((stage: AnyState) => stage.key !== "output" && stage.key !== "raw_output");
    const rawOutput = stages.find((stage: AnyState) => stage.key === "raw_output")?.detail || "";
    const csvPath = account.platform === "skland" ? "code/accounts.csv" : "config/accounts.csv";
    const platformDirty = csvDrafts.some((row: AnyState) => csvDirtyIds.has(row.id) && (isHoyo ? row.platform === "hoyolab" || row.platform === "miyoushe_cn" : row.platform === account.platform));
    const canSavePlatform = !!state.canAddAccount && (platformDirty || pageCredentialReady);
    return <>
      {backHeader(account.name || "未命名账号", platformLabel(account.platform) + " · 账号详情")}
      <section className="surface-card">
        <div className="card-heading"><span>{isHoyo ? "Cookie 管理" : "登录凭据管理"}</span></div>
        {isHoyo ? <button className="primary-button full-button" onClick={openLogin}><LogIn size={17}/> 打开 {account.platform === "miyoushe_cn" ? "米游社" : "HoYoLAB"} 登录页面</button>
          : <button className="primary-button full-button" onClick={openLogin}><LogIn size={17}/> 打开森空岛登录页面</button>}
        <div className="session-cookie-state">
          <span>本次应用运行期间获取的{credentialLabel}</span>
          <strong className={pageCredentialReady ? "good" : "muted"}>{pageCredentialReady ? "已获取" : "尚未获取"}</strong>
        </div>
        
        <div className="csv-actions">
          <button className="primary-button" onClick={saveCsv} disabled={!canSavePlatform}>
            <Cloud size={16}/> 保存并更新云函数
          </button>
        </div>

        {visibleStages.length ? <div className="stage-list">{visibleStages.map((stage: AnyState, index: number) => <div className="stage-row" key={stage.key || index}>
          <span className="stage-index">{index + 1}</span>
          <div className="stage-copy"><strong>{stage.label || "执行阶段"}</strong><small>{stage.detail || "暂无详细信息"}</small></div>
          <span className={"status-pill " + statusTone(stage.status || "")}>{stage.status || "未执行"}</span>
        </div>)}</div> : <p className="empty-inline">尚无此账号的云函数更新记录。</p>}
        {rawOutput && <>
          <div className="card-heading detail-log-heading"><span>函数原始输出</span><button className="text-action" onClick={() => copyText(rawOutput, "函数输出已复制")}><Copy size={14}/> 复制</button></div>
          <pre className="log-view">{rawOutput}</pre>
        </>}
        <p className="footnote">保存范围为当前平台的整份 CSV：HoYoLAB 国服与国际服共用一份 CSV，森空岛使用独立 CSV（{csvPath}）。</p>
      </section>

      <section className="surface-card">
        <div className="card-heading">账号信息</div>
        <label className="field-label" htmlFor="account-name">账号名称</label>
        <input id="account-name" value={draft.name || ""} onChange={e => updateCsvRow(account.id, {name: e.target.value})}/>
        <div className="info-line"><span>平台</span><strong>{platformLabel(account.platform)}</strong></div>
        <div className="info-line"><span>服务器</span><strong>{account.platform === "miyoushe_cn" ? "国服" : account.platform === "hoyolab" ? "国际服" : "不适用"}</strong></div>
        <div className="info-line"><span>账号标识</span><strong>{account.userId || "尚未识别"}</strong></div>
        <div className="info-line"><span>云端状态</span><strong>{account.cloudBacked ? "已同步到云端 CSV" : account.cloudEligible ? "登录已完成，等待上传" : "本地暂存，登录后才可上传"}</strong></div>
        <div className="field-label">启用游戏</div>
        <div className="csv-game-options detail-game-options">
          {options.map((game: AnyState) => <label className="csv-game-option" key={game.key}>
            <input type="checkbox" checked={selectedGames.has(game.key)} onChange={e => updateCsvGame(draft, game.key, e.target.checked)}/>
            <span>{game.label}</span>
          </label>)}
        </div>
        <label className="csv-enabled detail-enabled">
          <input type="checkbox" checked={!!draft.enabled} onChange={e => updateCsvRow(account.id, {enabled: e.target.checked})}/>
          <span>{draft.enabled ? "启用此账号签到" : "停用此账号签到"}</span>
        </label>
        <label className="field-label" htmlFor="account-remark">备注</label>
        <input id="account-remark" value={draft.remark || ""} onChange={e => updateCsvRow(account.id, {remark: e.target.value})} placeholder="添加备注"/>
        <p className="helper-text">未完成 Cookie/Token 获取与解析的新账号仅保留在本机；即使更新其他账号的云函数，也不会上传这条记录。登录成功后，它才会在后续平台 CSV 更新中一并上传。</p>
        <button className="csv-delete-button detail-delete-button" onClick={() => callNative("deletePlatformAccount", account.id)} disabled={!state.canAddAccount}>删除此账号</button>
      </section>
    </>;
  };
  const renderCloud = () => <>
    {title("华为云", "管理云端连接与访问凭据。")}
    <section className="surface-card">
      <div className="card-heading"><span>云端凭据</span><Settings2 size={17}/></div>
      <label className="field-label" htmlFor="cloud-region">区域</label>
      <select id="cloud-region" value={cloudDraft.region} onChange={e => setCloudDraft({...cloudDraft,region:e.target.value})}>
        <option value="cn-east-3">华东-上海一</option><option value="cn-east-2">华东-上海二</option><option value="cn-north-4">华北-北京四</option><option value="cn-north-1">华北-北京一</option><option value="cn-south-1">华南-广州</option><option value="ap-southeast-1">中国-香港</option><option value="ap-southeast-3">亚太-新加坡</option>
      </select>
      <label className="field-label" htmlFor="cloud-ak">访问密钥 ID（AK）</label>
      <input id="cloud-ak" autoComplete="off" autoCapitalize="off" placeholder="输入访问密钥 ID" value={cloudDraft.ak} onChange={e => setCloudDraft({...cloudDraft,ak:e.target.value})}/>
      <label className="field-label" htmlFor="cloud-sk">秘密访问密钥（SK）</label>
      <input id="cloud-sk" type="password" autoComplete="off" placeholder="输入秘密访问密钥" value={cloudDraft.sk} onChange={e => setCloudDraft({...cloudDraft,sk:e.target.value})}/>
      <p className="helper-text">请在华为云控制台的「我的凭证」中获取 AK 和 SK。</p>
      <button className="primary-button full-button" onClick={submitCloudLogin}><Cloud size={17}/>连接华为云</button>
      <div className="connection-state"><span className={"connection-dot " + (state.cloudConnected ? "connected" : "")}/>{state.cloudConnected ? "已连接" : state.cloudConnectionLabel || "未连接"}</div>
    </section>
  </>;




  return <div className="app-shell">
    <header className="topbar">
      <div className="brand-mark"><CalendarCheck size={19}/></div>
      <div className="brand-copy"><strong>游戏自动签到</strong></div>
      {(selectedId || selectedTodayId) && <button className="icon-button header-back" onClick={goBack} aria-label="返回"><ArrowLeft size={18}/></button>}
    </header>
    <main className="main-content">
      {page === "cloud" ? renderCloud() : selectedTodayId ? renderTodayDetail() : selectedId ? renderDetail() : renderHome()}
    </main>
    {!selectedId && !selectedTodayId && <nav className="bottom-nav">
      <button className={page === "home" ? "active" : ""} onClick={() => setPage("home")}><Users size={19}/><span>账号</span></button>
      <button className={page === "cloud" ? "active" : ""} onClick={() => setPage("cloud")}><Cloud size={19}/><span>华为云</span></button>
    </nav>}

    {modal === "add" && <div className="modal-backdrop" onClick={closeModal}>
      <section className="modal-sheet" onClick={e => e.stopPropagation()}>
        <div className="modal-title"><div><h2>添加账号</h2><p>选择需要添加的平台。</p></div><button className="icon-button" onClick={closeModal} aria-label="关闭"><X size={18}/></button></div>
        {csvPlatform === "hoyolab" ? <>
          <button className="choice-row" onClick={() => addAccount("miyoushe_cn")}><span><strong>米游社国服</strong><small>使用米游社账号登录</small></span><ChevronRight size={17}/></button>
          <button className="choice-row" onClick={() => addAccount("hoyolab")}><span><strong>HoYoLAB 国际服</strong><small>使用 HoYoLAB 账号登录</small></span><ChevronRight size={17}/></button>
        </> : <button className="choice-row" onClick={() => addAccount("skland")}><span><strong>森空岛</strong><small>明日方舟 / 终末地</small></span><ChevronRight size={17}/></button>}
      </section>
    </div>}



    {toast && <div className="toast-message" role="status">{toast}</div>}
  </div>;
}
