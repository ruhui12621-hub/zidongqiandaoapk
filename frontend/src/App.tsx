import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { flushSync } from "react-dom";
import GlobalManagement from "./GlobalManagement";
import {
  ArrowLeft, CalendarCheck, Check, CheckCircle2, ChevronRight, Cloud,
  Copy, FileText, Gamepad2, LoaderCircle, LogIn, Plus, RefreshCw,
  Settings2, Users, X, AlertCircle
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
  const [expandedTodayAccountId, setExpandedTodayAccountId] = useState<string | null>(null);
  const [modal, setModal] = useState<"add" | "games" | null>(null);
  const [gameDraft, setGameDraft] = useState<string[] | null>(null);
  const [cloudDraft, setCloudDraft] = useState({ region: "cn-east-3", ak: "", sk: "" });
  const [cloudLoaded, setCloudLoaded] = useState(false);
  const [toast, setToast] = useState("");
  const [csvPlatform, setCsvPlatform] = useState("hoyolab");
  const [csvDrafts, setCsvDrafts] = useState<AnyState[]>([]);
  const [csvDirtyIds, setCsvDirtyIds] = useState<Set<string>>(new Set());
  const accountSnapshot = JSON.stringify((state.accounts || []).map((row: AnyState) => ({ id: row.id, platform: row.platform, name: row.name, userId: row.userId, games: row.games, enabled: row.enabled, remark: row.remark, cookieReady: row.cookieReady, tokenReady: row.tokenReady })));

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
    setPage("home"); setSelectedId(null); setExpandedTodayAccountId(null); setGameDraft(null);
    callNative("backToHome");
  };

  // Return both the React screen and native account state to the account-list tab.
  const goToAccountList = useCallback(() => {
    // Commit the destination immediately; this navigation is React-only and
    // must not wait for the Android JavaScript bridge.
    flushSync(() => {
      setSelectedId(null);
      setTodayGame({ key: null, label: null });
      setGameDraft(null);
      setPage("home");
    });
    window.scrollTo({ top: 0, left: 0, behavior: "instant" as ScrollBehavior });
  }, []);
  const closeModal = () => { if (modal === "games") setGameDraft(null); setModal(null); };
  const goBack = () => {
    if (modal) { closeModal(); return; }
    if (selectedId) { setSelectedId(null); setPage("platform-accounts"); setGameDraft(null); return; }
    if (page === "platform-accounts") { setPage("home"); return; }
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

  const showGamePicker = () => {
    if (!account) return;
    setGameDraft((account.games || []).map((g: AnyState) => g.key));
    setModal("games");
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
    callNative("savePlatformAccounts", csvPlatform, JSON.stringify(payload));
    notify("正在保存 CSV 并更新平台云函数");
  };

  const renderHome = () => <>
    {title("选择平台", "选择平台后管理该平台的全部账号。")}
    <button className="cloud-banner" onClick={() => setPage("cloud")}>
      <span className="banner-icon"><Cloud size={21}/></span>
      <span className="banner-copy"><strong>华为云</strong><small>{state.syncLabel || "等待连接华为云"}</small></span>
      <span className={"status-pill " + (state.cloudConnected ? "good" : "muted")}>{state.cloudConnected ? "已连接" : state.cloudCredentialsSaved ? "凭据已保存" : "未连接"}</span>
      <ChevronRight size={18} className="subtle-icon"/>
    </button>
    <button className="cloud-banner" onClick={() => {setCsvPlatform("hoyolab");setPage("platform-accounts");}}>
      <span className="banner-icon"><Gamepad2 size={21}/></span>
      <span className="banner-copy"><strong>HoYoLAB</strong><small>国服与国际服 · 共用一份 CSV</small></span>
      <span className="status-pill muted">{(state.accounts || []).filter((a: AnyState) => a.platform === "hoyolab" || a.platform === "miyoushe_cn").length} 个账号</span>
      <ChevronRight size={18} className="subtle-icon"/>
    </button>
    <button className="cloud-banner" onClick={() => {setCsvPlatform("skland");setPage("platform-accounts");}}>
      <span className="banner-icon"><Gamepad2 size={21}/></span>
      <span className="banner-copy"><strong>森空岛</strong><small>明日方舟 / 终末地</small></span>
      <span className="status-pill muted">{(state.accounts || []).filter((a: AnyState) => a.platform === "skland").length} 个账号</span>
      <ChevronRight size={18} className="subtle-icon"/>
    </button>
  </>;
  const renderPlatformAccounts = () => {
    const accounts = state.accounts || [];
    const rows = csvPlatform === "skland" ? csvDrafts.filter((item: AnyState) => item.platform === "skland") : csvDrafts.filter((item: AnyState) => item.platform === "hoyolab" || item.platform === "miyoushe_cn");
    const liveRows = csvPlatform === "skland" ? accounts.filter((item: AnyState) => item.platform === "skland") : accounts.filter((item: AnyState) => item.platform === "hoyolab" || item.platform === "miyoushe_cn");
    const platformLabel = csvPlatform === "hoyolab" ? "HoYoLAB" : "森空岛";
    const csvPath = csvPlatform === "skland" ? "code/accounts.csv" : "config/accounts.csv";
    const stageOwner = liveRows.find((row: AnyState) => (row.cloudUpdateStages || []).some((stage: AnyState) => stage.status !== "未执行" || stage.detail));
    const stages = (stageOwner?.cloudUpdateStages || []).filter((stage: AnyState) => stage.key !== "output");
    return <>
      {backHeader(platformLabel, `${csvPath} · ${rows.length} 个账号`)}
      <button className="cloud-banner" onClick={() => setPage("cloud")}>
        <span className="banner-icon"><Cloud size={21}/></span><span className="banner-copy"><strong>华为云</strong><small>{state.syncLabel || "等待连接华为云"}</small></span>
        <span className={"status-pill " + (state.cloudConnected ? "good" : "muted")}>{state.cloudConnected ? "已连接" : state.cloudCredentialsSaved ? "凭据已保存" : "未连接"}</span><ChevronRight size={18} className="subtle-icon"/>
      </button>
      <section className="surface-card">
        <div className="card-heading"><span>今日签到情况</span>
          <button className="text-action" onClick={() => {callNative("refreshTodayStatuses");notify("正在读取今日云函数日志");}} disabled={state.todayQuerying}>
            {state.todayQuerying ? <LoaderCircle className="spin" size={16}/> : <RefreshCw size={16}/>} 刷新
          </button>
        </div>
        <p className="helper-text">{state.todayQuerying ? "正在读取并分析今日云函数日志…" : state.todaySummary || "结果来自平台独立云函数的今日运行日志；无法明确匹配的记录会显示为待确认。"}</p>
        {liveRows.length ? <div className="result-table"><table><thead><tr><th>账号</th><th>服区</th><th>今日签到</th><th>游戏</th></tr></thead><tbody>
          {liveRows.map((row: AnyState) => {
            const expanded = expandedTodayAccountId === row.id;
            const details = row.todayDetails || {};
            const gameDetails = details.games || {};
            const logs = Array.isArray(details.allRawMessages) ? details.allRawMessages.join("\n") : safe(details.allRawMessages);
            return <React.Fragment key={row.id}>
              <tr className="today-account-row" onClick={() => setExpandedTodayAccountId(expanded ? null : row.id)}>
                <td>{row.name}</td><td>{row.platform === "miyoushe_cn" ? "国服" : row.platform === "hoyolab" ? "国际服" : "—"}</td>
                <td><span className={"result-label " + statusTone(row.todayStatus || "")}>{row.todayStatus || "今日状态未知"}</span></td>
                <td>{(row.games || []).length} 款 · {expanded ? "收起" : "查看"}</td>
              </tr>
              {expanded && <tr><td colSpan={4}><div className="today-account-detail">
                <div className="info-line"><span>日志组</span><strong>{details.logGroupName || "未获取"}</strong></div>
                <div className="info-line"><span>日志流</span><strong>{details.logStreamName || "未获取"}</strong></div>
                <div className="result-table"><table><thead><tr><th>游戏</th><th>状态</th><th>日志判断依据</th></tr></thead><tbody>
                  {(row.games || []).map((game: AnyState) => {const key = game.key || game.id; const status = row.gameTodayStatuses?.[key] || "unknown"; return <tr key={key}><td>{game.label || key}</td><td><span className={"result-label " + gameTone(status)}>{gameStatusText(status)}</span></td><td>{gameDetails[key]?.reason || "没有获取到明确的游戏级日志结果。"}</td></tr>;})}
                </tbody></table></div>
                <div className="card-heading"><span>匹配到的云函数原始日志</span><button className="text-action" onClick={() => copyText(logs, "签到日志已复制")}><Copy size={14}/> 复制</button></div>
                <pre className="log-view">{logs || details.reason || "没有读取到匹配此账号的日志。"}</pre>
              </div></td></tr>}
            </React.Fragment>;
          })}
        </tbody></table></div> : <p className="empty-inline">当前平台暂无账号，添加账号后会在这里显示签到结果。</p>}
      </section>
      <section className="surface-card">
        <div className="card-heading"><span>云函数更新与执行情况</span><span className="subtle-caption">{stages.length ? `${stages.length} 个步骤` : "等待更新"}</span></div>
        <p className="helper-text">保存 CSV 时会一并更新当前平台云函数、检查日志隔离、执行测试并检查定时触发器。</p>
        {stages.length ? <div className="stage-list">{stages.map((stage: AnyState, index: number) => <div className="stage-row" key={stage.key || index}>
          <span className="stage-index">{index + 1}</span><div className="stage-copy"><strong>{stage.label || "执行阶段"}</strong><small>{stage.detail || "暂无详细信息"}</small></div><span className={"status-pill " + statusTone(stage.status || "")}>{stage.status || "未执行"}</span>
        </div>)}</div> : <p className="empty-inline">尚无本平台云函数更新记录。</p>}
      </section>
      <div className="csv-file-label"><FileText size={14}/><span>{csvPath}</span><small>{rows.length} 行</small></div>
      <div className="section-heading"><div><span>{rows.length} 个账号</span></div></div>
      {rows.length === 0 ? <div className="empty-state"><span className="empty-symbol"><Users size={27}/></span><strong>{state.syncState === "syncing" ? "正在读取云端 CSV" : state.syncState === "failed" ? "读取云端 CSV 失败" : "当前平台暂无账号行"}</strong><p>{state.syncState === "failed" ? (state.syncLabel || "请检查华为云连接和函数配置。") : "添加一行账号后，可以在表格中编辑名称、游戏、启用状态和备注。凭据通过对应平台的登录页面获取。"}</p></div> : <div className="csv-table-wrap">
        <table className="csv-editor-table"><thead><tr><th>账号名称</th><th>服区</th><th>账号标识</th><th>凭据</th><th>游戏选择</th><th>启用</th><th>备注</th><th>操作</th></tr></thead><tbody>
          {rows.map((row: AnyState) => {const options = getCsvGameOptions(row.platform); const selected = (row.games || []).map((game: AnyState) => game.key || game); const credentialReady = row.platform === "skland" ? !!row.tokenReady : !!row.cookieReady; return <tr key={row.id}>
            <td><input className="csv-cell-input csv-name-input" value={row.name || ""} onChange={e => updateCsvRow(row.id, {name:e.target.value})} aria-label="账号名称"/></td>
            <td><span className="csv-user-id">{row.platform === "miyoushe_cn" ? "国服" : row.platform === "hoyolab" ? "国际服" : "—"}</span></td>
            <td><span className="csv-user-id">{row.userId || "未识别"}</span></td>
            <td><span className={"csv-credential " + (credentialReady ? "ready" : "missing")}>{credentialReady ? "已配置" : "未获取"}</span></td>
            <td><div className="csv-game-options">{options.map((game: AnyState) => <label className="csv-game-option" key={game.key}><input type="checkbox" checked={selected.includes(game.key)} onChange={e => updateCsvGame(row, game.key, e.target.checked)}/><span>{game.label}</span></label>)}</div></td>
            <td><label className="csv-enabled"><input type="checkbox" checked={!!row.enabled} onChange={e => updateCsvRow(row.id, {enabled:e.target.checked})}/><span>{row.enabled ? "启用" : "停用"}</span></label></td>
            <td><input className="csv-cell-input csv-remark-input" value={row.remark || ""} onChange={e => updateCsvRow(row.id, {remark:e.target.value})} placeholder="备注" aria-label="账号备注"/></td>
            <td><div className="csv-row-actions"><button className="csv-detail-button" onClick={() => {setSelectedId(row.id);setPage("platform-accounts");setGameDraft(null);}}>详情</button><button className="csv-delete-button" disabled={!state.canAddAccount} onClick={() => {callNative("deletePlatformAccount", row.id);notify("已提交删除请求");}}>删除</button></div></td>
          </tr>;})}
        </tbody></table>
      </div>}
      <div className="csv-actions">
        <button className="secondary-button" onClick={() => {setCsvDrafts(state.accounts || []);setCsvDirtyIds(new Set());}} disabled={!state.canAddAccount}><RefreshCw size={16}/> 还原编辑</button>
        <button className="secondary-button" onClick={() => state.canAddAccount ? setModal("add") : notify("请先成功读取云端配置")} disabled={!state.canAddAccount}><Plus size={16}/> 添加行</button>
        <button className="primary-button" onClick={saveCsv} disabled={!state.canAddAccount || !rows.length}><Cloud size={16}/> 保存 CSV 并更新云函数</button>
      </div>
      <p className="footnote">保存 CSV 是当前平台唯一的更新入口：账号信息、游戏选择与已获取凭据会合并写入平台 CSV，随后更新云函数并检查执行配置。HoYoLAB 国服和国际服通过 server 字段区分，森空岛使用独立 CSV。</p>
    </>;
  };
  const renderDetail = () => {
    if (!account) return renderHome();
    const isHoyo = account.platform === "hoyolab" || account.platform === "miyoushe_cn";
    const credentialReady = isHoyo ? !!account.cookieReady : !!account.tokenReady;
    const pageCookieReady = !!(state.hoyoPageCookieReady && state.hoyoPageCookieAccountId === account.id);
    return <>
      {backHeader(account.name, account.platformLabel + " · 账号详情")}
      <section className="surface-card">
        <div className="card-heading"><span>账号凭据</span><span className={"status-pill " + (credentialReady ? "good" : "muted")}>{credentialReady ? "已获取" : "未获取"}</span></div>
        {isHoyo ? <>
          <button className="primary-button full-button" onClick={openLogin}><LogIn size={17}/> 打开 {account.platform === "miyoushe_cn" ? "米游社" : "HoYoLAB"} 登录页面</button>
          <div className="inline-status">{pageCookieReady ? <><CheckCircle2 size={14}/> 本次登录页面已获取 Cookie</> : <><AlertCircle size={14}/> 等待本次登录页面获取 Cookie</>}</div>
        </> : <button className="primary-button full-button" onClick={openLogin}><LogIn size={17}/> 打开森空岛登录页面</button>}
      </section>
      <section className="surface-card">
        <div className="card-heading">账号信息</div>
        <div className="info-line"><span>平台</span><strong>{account.platformLabel}</strong></div>
        <div className="info-line"><span>账号标识</span><strong>{account.userId || "未识别"}</strong></div>
        <div className="info-line"><span>已启用游戏</span><strong>{(account.games || []).map((game: AnyState) => game.label || game.key).join("、") || "未配置"}</strong></div>
        <p className="helper-text">游戏、启用状态、名称和备注请在上一级账号列表编辑；保存 CSV 时会统一更新平台云函数。</p>
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

  const renderMessages = () => <>
    {title("运行消息", "查看操作记录与错误信息。")}
    <section className="surface-card">
      <div className="card-heading"><span>消息内容</span><button className="icon-button small" onClick={() => copyText(state.allMessages || "", "全部运行消息已复制")}><Copy size={15}/></button></div>
      <pre className="log-view all-messages">{state.allMessages || "暂无运行消息"}</pre>
    </section>
  </>;

  const options = account
    ? account.platform === "skland"
      ? state.gameCatalog?.skland || []
      : account.platform === "miyoushe_cn"
        ? state.gameCatalog?.hoyolab_cn || []
        : state.gameCatalog?.hoyolab_os || []
    : [];

  return <div className="app-shell">
    <header className="topbar">
      <div className="brand-mark"><CalendarCheck size={19}/></div>
      <div className="brand-copy"><strong>游戏自动签到</strong></div>
      {selectedId && <button className="icon-button header-back" onClick={goBack} aria-label="返回"><ArrowLeft size={18}/></button>}
    </header>
    <main className="main-content">
      {page === "cloud" ? renderCloud() : page === "messages" ? renderMessages() : page === "management" ? <GlobalManagement accounts={state.accounts || []} gameCatalog={state.gameCatalog || {}} onGoToAccountList={goToAccountList} /> : selectedId ? renderDetail() : page === "platform-accounts" ? renderPlatformAccounts() : renderHome()}
    </main>
    {!selectedId && <nav className="bottom-nav">
      <button className={page === "home" ? "active" : ""} onClick={() => setPage("home")}><Users size={19}/><span>账号</span></button>
      <button className={page === "cloud" ? "active" : ""} onClick={() => setPage("cloud")}><Cloud size={19}/><span>华为云</span></button>
      <button className={page === "messages" ? "active" : ""} onClick={() => setPage("messages")}><FileText size={19}/><span>运行消息</span></button>
      <button className={page === "management" ? "active" : ""} onClick={() => setPage("management")}><Settings2 size={19}/><span>全局管理</span></button>
    </nav>}

    {modal === "add" && <div className="modal-backdrop" onClick={closeModal}>
      <section className="modal-sheet" onClick={e => e.stopPropagation()}>
        <div className="modal-title"><div><h2>添加账号</h2><p>选择需要添加的平台。</p></div><button className="icon-button" onClick={closeModal} aria-label="关闭"><X size={18}/></button></div>
        {csvPlatform === "hoyolab" ? <>
          <button className="choice-row" onClick={() => addAccount("miyoushe_cn")}><span><strong>HoYoLAB 国服</strong><small>国服账号（server=cn）</small></span><ChevronRight size={17}/></button>
          <button className="choice-row" onClick={() => addAccount("hoyolab")}><span><strong>HoYoLAB 国际服</strong><small>国际服账号（server=os）</small></span><ChevronRight size={17}/></button>
        </> : <button className="choice-row" onClick={() => addAccount("skland")}><span><strong>森空岛</strong><small>明日方舟 / 终末地</small></span><ChevronRight size={17}/></button>}
      </section>
    </div>}

    {modal === "games" && <div className="modal-backdrop" onClick={closeModal}>
      <section className="modal-sheet game-sheet" onClick={e => e.stopPropagation()}>
        <div className="modal-title"><div><h2>选择要启用的游戏</h2><p>勾选需要参与自动签到的游戏。</p></div><button className="icon-button" onClick={closeModal} aria-label="关闭"><X size={18}/></button></div>
        <div className="game-options">{options.map((game: AnyState) => <label className="game-option" key={game.key}>
          <input type="checkbox" checked={(gameDraft || []).includes(game.key)} onChange={e => setGameDraft(old => e.target.checked ? [...(old || []),game.key] : (old || []).filter(x => x !== game.key))}/>
          <span>{game.label}</span><span className="checkmark"><Check size={14}/></span>
        </label>)}</div>
        <div className="modal-actions"><button className="secondary-button" onClick={closeModal}>取消</button><button className="primary-button" onClick={() => {if (account) updateCsvRow(account.id, {games: (gameDraft || []).map(key => ({key, label: options.find((item: AnyState) => item.key === key)?.label || key}))});setModal(null);setGameDraft(null);notify("游戏选择已暂存；返回账号列表保存 CSV 后生效");}}>完成</button></div>
      </section>
    </div>}

    {toast && <div className="toast-message" role="status">{toast}</div>}
  </div>;
}
