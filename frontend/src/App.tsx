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
  const [modal, setModal] = useState<"add" | null>(null);
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
    setPage("home"); setSelectedId(null); 
    callNative("backToHome");
  };

  // Return both the React screen and native account state to the account-list tab.
  const goToAccountList = useCallback(() => {
    // Commit the destination immediately; this navigation is React-only and
    // must not wait for the Android JavaScript bridge.
    flushSync(() => {
      setSelectedId(null);
      setPage("home");
    });
    window.scrollTo({ top: 0, left: 0, behavior: "instant" as ScrollBehavior });
  }, []);
  const closeModal = () => setModal(null);
  const goBack = () => {
    if (modal) { closeModal(); return; }
    if (selectedId) { setSelectedId(null); setPage("platform-accounts"); return; }
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
    const liveRows = csvPlatform === "skland"
      ? accounts.filter((item: AnyState) => item.platform === "skland")
      : accounts.filter((item: AnyState) => item.platform === "hoyolab" || item.platform === "miyoushe_cn");
    const platformLabel = csvPlatform === "hoyolab" ? "HoYoLAB" : "森空岛";
    return <>
      {backHeader(platformLabel, `${liveRows.length} 个账号`)}
      <section className="surface-card">
        <div className="card-heading">
          <span>今日签到情况</span>
          <button className="text-action" onClick={() => {callNative("refreshTodayStatuses");notify("正在读取今日云函数日志");}} disabled={state.todayQuerying}>
            {state.todayQuerying ? <LoaderCircle className="spin" size={16}/> : <RefreshCw size={16}/>} 刷新
          </button>
        </div>
        <p className="helper-text">{state.todayQuerying ? "正在读取并分析今日云函数日志…" : state.todaySummary || "结果来自当前平台云函数的今日运行日志。"}</p>
        {liveRows.length ? <div className="result-table"><table>
          <thead><tr><th>账号</th><th>服区</th><th>今日签到</th><th>游戏</th></tr></thead>
          <tbody>{liveRows.map((row: AnyState) => {
            const expanded = expandedTodayAccountId === row.id;
            const details = row.todayDetails || {};
            const gameDetails = details.games || {};
            const logs = Array.isArray(details.allRawMessages) ? details.allRawMessages.join(String.fromCharCode(10)) : safe(details.allRawMessages);
            const games = row.platform === "skland"
              ? (row.games || []).map((game: AnyState) => ({key: game.key || game.id, label: game.label || game.key || game.id}))
              : getCsvGameOptions(row.platform);
            const enabledKeys = new Set((row.games || []).map((game: AnyState) => game.key || game.id));
            return <React.Fragment key={row.id}>
              <tr className="today-account-row" onClick={() => setExpandedTodayAccountId(expanded ? null : row.id)}>
                <td>{row.name || "未命名账号"}</td>
                <td>{row.platform === "miyoushe_cn" ? "国服" : row.platform === "hoyolab" ? "国际服" : "—"}</td>
                <td><span className={"result-label " + statusTone(row.todayStatus || "")}>{row.todayStatus || "今日状态未知"}</span></td>
                <td>{(row.games || []).length} 款 · {expanded ? "收起" : "查看"}</td>
              </tr>
              {expanded && <tr><td colSpan={4}><div className="today-account-detail">
                <div className="info-line"><span>日志组</span><strong>{details.logGroupName || "未获取"}</strong></div>
                <div className="info-line"><span>日志流</span><strong>{details.logStreamName || "未获取"}</strong></div>
                <div className="result-table"><table><thead><tr><th>游戏</th><th>状态</th><th>日志判断依据</th></tr></thead><tbody>
                  {games.map((game: AnyState) => {
                    const key = game.key || game.id;
                    const enabled = enabledKeys.has(key);
                    const status = enabled ? (row.gameTodayStatuses?.[key] || "unknown") : "unconfigured";
                    return <tr key={key}><td>{game.label || key}</td><td><span className={"result-label " + gameTone(status)}>{gameStatusText(status)}</span></td><td>{enabled ? (gameDetails[key]?.reason || "没有获取到明确的游戏级日志结果。") : "该游戏未在此账号配置中启用签到。"}</td></tr>;
                  })}
                </tbody></table></div>
                <div className="card-heading"><span>匹配到的云函数原始日志</span><button className="text-action" onClick={() => copyText(logs, "签到日志已复制")}><Copy size={14}/> 复制</button></div>
                <pre className="log-view">{logs || details.reason || "没有读取到匹配此账号的日志。"}</pre>
              </div></td></tr>}
            </React.Fragment>;
          })}</tbody>
        </table></div> : <p className="empty-inline">当前平台暂无账号，添加账号后会在这里显示签到结果。</p>}
      </section>
      <section className="surface-card">
        <div className="card-heading">
          <span>账号列表</span>
          <button className="text-action" onClick={() => state.canAddAccount ? setModal("add") : notify("请先成功读取云端配置")} disabled={!state.canAddAccount}><Plus size={16}/> 添加账号</button>
        </div>
        {liveRows.length ? <div className="account-list">
          {liveRows.map((row: AnyState) => <button className="account-row" key={row.id} onClick={() => {
            setCsvPlatform(row.platform === "skland" ? "skland" : "hoyolab");
            setSelectedId(row.id);
          }}>
            <span className="account-info"><strong>{row.name || "未命名账号"}</strong></span>
            <ChevronRight size={18} className="subtle-icon"/>
          </button>)}
        </div> : <div className="empty-state">
          <span className="empty-symbol"><Users size={27}/></span>
          <strong>{state.syncState === "syncing" ? "正在读取云端账号" : state.syncState === "failed" ? "读取云端账号失败" : "当前平台暂无账号"}</strong>
          <p>{state.syncState === "failed" ? (state.syncLabel || "请检查华为云连接和函数配置。") : "添加账号后，点击账号名称进入详情，查看和修改账号信息。"}</p>
          <button className="primary-button full-button" onClick={() => state.canAddAccount ? setModal("add") : notify("请先成功读取云端配置")} disabled={!state.canAddAccount}><Plus size={16}/> 添加账号</button>
        </div>}
      </section>
    </>;
  };
  const renderDetail = () => {
    if (!account) return renderPlatformAccounts();
    const draft = csvDrafts.find((row: AnyState) => row.id === account.id) || account;
    const isHoyo = account.platform === "hoyolab" || account.platform === "miyoushe_cn";
    // Cookie acquisition is process-session state, never inferred from CSV credentials.
    const pageCookieReady = !!account.sessionCookieAcquired;
    const options = getCsvGameOptions(account.platform);
    const selectedGames = new Set((draft.games || []).map((game: AnyState) => game.key || game));
    const stages = account.cloudUpdateStages || [];
    const visibleStages = stages.filter((stage: AnyState) => stage.key !== "output" && stage.key !== "raw_output");
    const rawOutput = stages.find((stage: AnyState) => stage.key === "raw_output")?.detail || "";
    const csvPath = csvPlatform === "skland" ? "code/accounts.csv" : "config/accounts.csv";
    return <>
      {backHeader(account.name, account.platformLabel + " · 账号详情")}
      <section className="surface-card">
        <div className="card-heading"><span>Cookie 管理</span></div>
        {isHoyo ? <button className="primary-button full-button" onClick={openLogin}><LogIn size={17}/> 打开 {account.platform === "miyoushe_cn" ? "米游社" : "HoYoLAB"} 登录页面</button>
          : <button className="primary-button full-button" onClick={openLogin}><LogIn size={17}/> 打开森空岛登录页面</button>}
        {isHoyo ? <>
          <div className="info-line"><span>本次 APK 运行期间 Cookie</span><strong>{pageCookieReady ? "已获取" : "尚未获取"}</strong></div>
          <p className="helper-text">此状态只表示本次 APK 运行期间是否从登录页面实际获取到当前账号的 Cookie，不会根据云端已有 Cookie 推断。</p>
          <div className="info-line"><span>云端 CSV Cookie</span><strong>{account.cookieReady ? "已配置" : "未配置"}</strong></div>
        </> : <>
          <div className="info-line"><span>云端 CSV 登录令牌</span><strong>{account.tokenReady ? "已配置" : "未配置"}</strong></div>
        </>}
        <div className="card-heading">
          <span>{isHoyo ? "更新云端 Cookie" : "保存账号并更新云函数"}</span>
          <span className="subtle-caption">{visibleStages.length ? `${visibleStages.length} 个步骤` : "等待更新"}</span>
        </div>
        <p className="helper-text">{isHoyo
          ? "点击下方按钮后，将当前账号本次运行期间获取的 Cookie 写入云端 CSV，再更新并检查 HoYoLAB 云函数。若本次尚未获取新 Cookie，则保留云端原有 Cookie，不会把云端状态当成本次已获取。"
          : "保存当前账号修改，并将森空岛平台的全部账号统一写入 CSV，随后更新并检查对应云函数。"}</p>
        {visibleStages.length ? <div className="stage-list">{visibleStages.map((stage: AnyState, index: number) => <div className="stage-row" key={stage.key || index}>
          <span className="stage-index">{index + 1}</span>
          <div className="stage-copy"><strong>{stage.label || "执行阶段"}</strong><small>{stage.detail || "暂无详细信息"}</small></div>
          <span className={"status-pill " + statusTone(stage.status || "")}>{stage.status || "未执行"}</span>
        </div>)}</div> : <p className="empty-inline">尚无此账号的云函数更新记录。</p>}
        {rawOutput && <>
          <div className="card-heading detail-log-heading"><span>函数原始输出</span><button className="text-action" onClick={() => copyText(rawOutput, "函数输出已复制")}><Copy size={14}/> 复制</button></div>
          <pre className="log-view">{rawOutput}</pre>
        </>}
        <div className="csv-actions">
          <button className="primary-button" onClick={saveCsv} disabled={!state.canAddAccount || !csvDrafts.some((row: AnyState) => row.id === account.id)}>
            <Cloud size={16}/> 保存并更新云函数
          </button>
        </div>
        <p className="footnote">保存范围为当前平台的整份 CSV：HoYoLAB 国服与国际服共用一份 CSV，森空岛使用独立 CSV（{csvPath}）。</p>
      </section>

      <section className="surface-card">
        <div className="card-heading">账号信息</div>
        <label className="field-label" htmlFor="account-name">账号名称</label>
        <input id="account-name" value={draft.name || ""} onChange={e => updateCsvRow(account.id, {name: e.target.value})}/>
        <div className="info-line"><span>平台</span><strong>{account.platformLabel}</strong></div>
        <div className="info-line"><span>服务器</span><strong>{account.platform === "miyoushe_cn" ? "国服" : account.platform === "hoyolab" ? "国际服" : "—"}</strong></div>
        <div className="info-line"><span>账号标识</span><strong>{account.userId || "尚未识别"}</strong></div>
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
        <p className="helper-text">账号资料和游戏设置会在上方点击“保存并更新云函数”时写入平台 CSV。</p>
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

  const renderMessages = () => <>
    {title("运行消息", "查看操作记录与错误信息。")}
    <section className="surface-card">
      <div className="card-heading"><span>消息内容</span><button className="icon-button small" onClick={() => copyText(state.allMessages || "", "全部运行消息已复制")}><Copy size={15}/></button></div>
      <pre className="log-view all-messages">{state.allMessages || "暂无运行消息"}</pre>
    </section>
  </>;



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



    {toast && <div className="toast-message" role="status">{toast}</div>}
  </div>;
}
