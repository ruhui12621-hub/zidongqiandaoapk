import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
  const [todayGame, setTodayGame] = useState<{key: string | null; label: string | null}>({key:null,label:null});
  const [modal, setModal] = useState<"add" | "games" | null>(null);
  const [gameDraft, setGameDraft] = useState<string[] | null>(null);
  const [cloudDraft, setCloudDraft] = useState({ region: "cn-east-3", ak: "", sk: "" });
  const [cloudLoaded, setCloudLoaded] = useState(false);
  const [toast, setToast] = useState("");
  const [csvPlatform, setCsvPlatform] = useState("hoyolab");
  const [csvDrafts, setCsvDrafts] = useState<AnyState[]>([]);
  const accountSnapshot = JSON.stringify((state.accounts || []).map((row: AnyState) => ({ id: row.id, platform: row.platform, name: row.name, userId: row.userId, games: row.games, enabled: row.enabled, remark: row.remark, cookieReady: row.cookieReady, tokenReady: row.tokenReady })));

  useEffect(() => {
    setCsvDrafts(state.accounts || []);
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
    setPage("home"); setSelectedId(null); setTodayGame({key:null,label:null}); setGameDraft(null);
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
    if (page === "today-detail") { setPage("home"); setTodayGame({key:null,label:null}); return; }
    if (selectedId) { goHome(); return; }
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

  const deployHoyo = () => {
    if (!account) return;
    const pageCookieReady = !!(state.hoyoPageCookieReady && state.hoyoPageCookieAccountId === account.id);
    if (!pageCookieReady) { notify("请先打开本账号登录页面并成功获取 Cookie"); return; }
    if (!state.cloudConnected) { notify("请先连接华为云"); return; }
    callNative("deployHoyoAccount", account.id, JSON.stringify(gameDraft ?? (account.games || []).map((g: AnyState) => g.key)));
    notify("正在合并云端配置并上传");
  };

  const deploySkland = () => {
    if (!account) return;
    if (!account.tokenReady) { notify("请先打开本账号森空岛登录页面并成功获取 Token"); return; }
    if (!state.cloudConnected) { notify("请先连接华为云"); return; }
    const selectedGames = gameDraft ?? (account.games || []).map((g: AnyState) => g.key);
    callNative("deploySklandAccount", account.id, JSON.stringify(selectedGames));
    notify("正在同步森空岛账号、游戏选择并上传云函数");
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
    const rows = csvDrafts.filter((row: AnyState) => row.platform === csvPlatform);
    if (!rows.length) { notify("当前平台没有账号行"); return; }
    if (!state.cloudConnected) { notify("请先连接华为云"); return; }
    const payload = rows.map((row: AnyState) => ({
      id: row.id,
      name: row.name || "",
      games: (row.games || []).map((game: AnyState) => game.key || game),
      enabled: !!row.enabled,
      remark: row.remark || ""
    }));
    callNative("savePlatformAccounts", csvPlatform, JSON.stringify(payload));
    notify("已提交 CSV 校验和云函数更新，请查看运行消息");
  };

  const renderHome = () => {
    const accounts = state.accounts || [];
    const rows = csvDrafts.filter((item: AnyState) => item.platform === csvPlatform);
    const platformLabel = csvPlatform === "hoyolab" ? "HoYoLAB" : csvPlatform === "miyoushe_cn" ? "米游社国服" : "森空岛";
    const csvPath = csvPlatform === "skland" ? "code/accounts.csv" : "config/accounts.csv";
    return <>
      {title("账号管理", "按平台直接编辑云端 accounts.csv。")}
      <button className="cloud-banner" onClick={() => setPage("cloud")}>
        <span className="banner-icon"><Cloud size={21}/></span>
        <span className="banner-copy"><strong>华为云</strong><small>{state.syncLabel || "等待连接华为云"}</small></span>
        <span className={"status-pill " + (state.cloudConnected ? "good" : "muted")}>{state.cloudConnected ? "已连接" : state.cloudCredentialsSaved ? "凭据已保存" : "未连接"}</span>
        <ChevronRight size={18} className="subtle-icon"/>
      </button>
      <div className="csv-toolbar">
        <div className="csv-platform-tabs" role="tablist" aria-label="账号平台">
          {[
            ["hoyolab", "HoYoLAB"],
            ["miyoushe_cn", "米游社"],
            ["skland", "森空岛"]
          ].map(([key, label]) => <button key={key} className={csvPlatform === key ? "active" : ""} onClick={() => setCsvPlatform(key)}>{label}</button>)}
        </div>
        <div className="csv-file-label"><FileText size={14}/><span>{csvPath}</span><small>{rows.length} 行</small></div>
      </div>
      <div className="section-heading">
        <div><span>{accounts.length} 个账号</span></div>
        <button className="text-action" onClick={() => {callNative("refreshTodayStatuses");notify("正在刷新今日签到状态");}} disabled={state.todayQuerying}>
          {state.todayQuerying ? <LoaderCircle className="spin" size={16}/> : <RefreshCw size={16}/>} 刷新签到状态
        </button>
      </div>
      {rows.length === 0 ? <div className="empty-state">
        <span className="empty-symbol"><Users size={27}/></span>
        <strong>{state.syncState === "syncing" ? "正在读取云端 CSV" : state.syncState === "failed" ? "读取云端 CSV 失败" : "当前平台暂无账号行"}</strong>
        <p>{state.syncState === "failed" ? (state.syncLabel || "请检查华为云连接和函数配置。") : "添加一行账号后，可以在表格中编辑名称、游戏、启用状态和备注。凭据通过对应平台的登录页面获取。"}</p>
      </div> : <div className="csv-table-wrap">
        <table className="csv-editor-table">
          <thead><tr>
            <th>账号名称</th><th>平台用户 ID</th><th>凭据</th><th>游戏选择</th><th>启用</th><th>备注</th><th>操作</th>
          </tr></thead>
          <tbody>{rows.map((row: AnyState) => {
            const options = getCsvGameOptions(row.platform);
            const selected = (row.games || []).map((game: AnyState) => game.key || game);
            const credentialReady = row.platform === "skland" ? !!row.tokenReady : !!row.cookieReady;
            return <tr key={row.id}>
              <td><input className="csv-cell-input csv-name-input" value={row.name || ""} onChange={e => updateCsvRow(row.id, {name:e.target.value})} aria-label="账号名称"/></td>
              <td><span className="csv-user-id">{row.userId || "未识别"}</span></td>
              <td><span className={"csv-credential " + (credentialReady ? "ready" : "missing")}>{credentialReady ? "已保存" : "未获取"}</span></td>
              <td><div className="csv-game-options">{options.map((game: AnyState) => <label className="csv-game-option" key={game.key}>
                <input type="checkbox" checked={selected.includes(game.key)} onChange={e => updateCsvGame(row, game.key, e.target.checked)}/>
                <span>{game.label}</span>
              </label>)}</div></td>
              <td><label className="csv-enabled"><input type="checkbox" checked={!!row.enabled} onChange={e => updateCsvRow(row.id, {enabled:e.target.checked})}/><span>{row.enabled ? "启用" : "停用"}</span></label></td>
              <td><input className="csv-cell-input csv-remark-input" value={row.remark || ""} onChange={e => updateCsvRow(row.id, {remark:e.target.value})} placeholder="备注" aria-label="账号备注"/></td>
              <td><div className="csv-row-actions">
                <button className="csv-detail-button" onClick={() => {setSelectedId(row.id);setPage("home");setGameDraft(null);}}>详情</button>
                <button className="csv-delete-button" disabled={!state.canAddAccount} onClick={() => {callNative("deletePlatformAccount", row.id);notify("已提交删除请求");}}>删除</button>
              </div></td>
            </tr>;
          })}</tbody>
        </table>
      </div>}
      <div className="csv-actions">
        <button className="secondary-button" onClick={() => state.canAddAccount ? addAccount(csvPlatform) : notify("请先成功读取云端配置")} disabled={!state.canAddAccount}>
          <Plus size={16}/> 添加行
        </button>
        <button className="primary-button" onClick={saveCsv} disabled={!state.canAddAccount || !rows.length}>
          <Cloud size={16}/> 保存 CSV 并上传
        </button>
      </div>
      <p className="footnote">凭据在表格中默认隐藏；HoYoLAB 与米游社共用一个平台函数和 CSV，森空岛使用独立 CSV。保存时会重新上传完整 ZIP，但只更新对应平台的账号表。</p>
    </>;
  };

  const renderTodayDetail = () => {
    if (!account) return null;
    const data = account.todayDetails || {};
    const games = data.games || {};
    const selected = todayGame.key ? games[todayGame.key] : null;
    const game = (account.games || []).find((g: AnyState) => (g.key || g.id) === todayGame.key);
    const fallback = todayGame.key ? (!game ? "未配置" : account.gameTodayStatuses?.[todayGame.key] === "success" ? "今日签到成功" : account.gameTodayStatuses?.[todayGame.key] === "failed" ? "今日签到失败" : "未获取到明确结果") : data.status || account.todayStatus || "今日状态未知";
    const status = selected?.status || fallback;
    const reason = selected?.reason || (!game && todayGame.key ? "该游戏未在此账号配置中启用签到。" : "没有找到能够明确判断该游戏今日签到结果的日志。");
    const logs = Array.isArray(data.allRawMessages) ? data.allRawMessages.join("\n") : safe(data.allRawMessages);
    return <>
      {backHeader(todayGame.label || game?.label || todayGame.key || "账号签到详情", account.name + " · " + account.platformLabel + " · 今日签到详情")}
      <section className="surface-card">
        <div className="card-heading"><span>签到结果</span><span className={"status-pill " + statusTone(status)}>{status}</span></div>
        <p className="explanation">{reason}</p>
      </section>
      <section className="surface-card">
        <div className="card-heading">日志来源</div>
        <div className="info-line"><span>日志组</span><strong>{data.logGroupName || "未获取"}</strong></div>
        <div className="info-line"><span>日志流</span><strong>{data.logStreamName || "未获取"}</strong></div>
      </section>
      <section className="surface-card">
        <div className="card-heading">云函数今日日志</div>
        <pre className="log-view">{logs || "没有读取到云函数日志。"}</pre>
        <button className="secondary-button full-button" onClick={() => copyText(logs, "日志已复制")}><Copy size={16}/> 复制云函数日志</button>
      </section>
    </>;
  };

  const renderDetail = () => {
    if (!account) return renderHome();
    const isHoyo = account.platform === "hoyolab" || account.platform === "miyoushe_cn";
    const gameStates = account.gameTodayStatuses || {};
    const gameRows = (account.games || []).map((game: AnyState) => {
      const key = game.key || game.id || game.label;
      const result = gameStates[key] || "unknown";
      return <tr key={key} onClick={() => {setTodayGame({key, label:game.label || key});setPage("today-detail");}}>
        <td>{game.label || key}</td><td><span className={"result-label " + gameTone(result)}>{gameStatusText(result)}</span></td>
      </tr>;
    });
    const allOptions = account.platform === "miyoushe_cn" ? state.gameCatalog?.hoyolab_cn || [] : state.gameCatalog?.hoyolab_os || [];
    const cookieReady = !!account.cookieReady;
    const pageCookieReady = !!(state.hoyoPageCookieReady && state.hoyoPageCookieAccountId === account.id);
    const stages = (account.cloudUpdateStages || []).filter((s: AnyState) => s.key !== "output");
    return <>
      {backHeader(account.name, account.platformLabel + " · 账号详情")}
      <section className="surface-card">
        <div className="card-heading"><span>签到状态</span><span className="subtle-caption">点击游戏查看详情</span></div>
        {isHoyo ? <div className="result-table"><table><thead><tr><th>游戏</th><th>今日状态</th></tr></thead><tbody>{allOptions.map((g: AnyState) => {
          const enabled = (account.games || []).some((x: AnyState) => x.key === g.key);
          const result = enabled ? (gameStates[g.key] || "unknown") : "unconfigured";
          return <tr key={g.key} onClick={() => {setTodayGame({key:g.key,label:g.label});setPage("today-detail");}}>
            <td>{g.label}</td><td><span className={"result-label " + gameTone(result)}>{gameStatusText(result)}</span></td>
          </tr>;
        })}</tbody></table></div> : <div className="result-table"><table><thead><tr><th>游戏</th><th>今日状态</th></tr></thead><tbody>{gameRows.length ? gameRows : <tr><td colSpan={2} className="empty-cell">云端暂未配置签到游戏</td></tr>}</tbody></table></div>}
      </section>
      <section className="surface-card">
        <div className="card-heading">登录凭据与云函数</div>
        <div className="credential-status">
          <span>{isHoyo ? "Cookie 状态（本次运行）" : "Token 状态"}</span>
          <strong className={((isHoyo ? cookieReady : account.tokenReady) ? "text-good" : "text-warn")}>
            {isHoyo ? (cookieReady ? "已获取" : "未获取") : (account.tokenReady ? "已获取" : "未获取")}
          </strong>
        </div>
        {isHoyo ? <>
          <button className="primary-button full-button" onClick={openLogin}><LogIn size={17}/> 打开 {account.platform === "miyoushe_cn" ? "米游社" : "HoYoLAB"} 登录页面</button>
          <button className="secondary-button full-button" onClick={showGamePicker}><Gamepad2 size={17}/> 选择要启用的游戏</button>
          <p className="helper-text">默认勾选云端当前启用的游戏。上传时会同时更新游戏选择和本次网页 Cookie；其他云端配置保持不变。</p>
          <button className="primary-button full-button" onClick={deployHoyo}><Cloud size={17}/> 更新云函数并上传</button>
          <div className="inline-status">{pageCookieReady ? <><CheckCircle2 size={14}/> 本次登录页面已获取 Cookie</> : <><AlertCircle size={14}/> 等待本次登录页面获取 Cookie</>}</div>
        </> : <>
          <button className="primary-button full-button" onClick={openLogin}><LogIn size={17}/> 打开森空岛登录页面</button>
          <button className="secondary-button full-button" onClick={showGamePicker}><Gamepad2 size={17}/> 选择要启用的游戏</button>
          <p className="helper-text">选择会与当前账号 Token 一起同步到森空岛云函数；其他账号的游戏选择保持不变。</p>
          <button className="primary-button full-button" onClick={deploySkland}><Cloud size={17}/> 更新云函数并上传</button>
        </>}
      </section>
      <section className="surface-card">
        <div className="card-heading"><span>云函数执行情况</span><span className="subtle-caption">{stages.length} 个步骤</span></div>
        {stages.length ? <div className="stage-list">{stages.map((s: AnyState, index: number) => <div className="stage-row" key={s.key || index}>
          <span className="stage-index">{index + 1}</span><div className="stage-copy"><strong>{s.label || "执行阶段"}</strong><small>{s.detail || "暂无详细信息"}</small></div>
          <span className={"status-pill " + statusTone(s.status || "")}>{s.status || "未执行"}</span>
        </div>)}</div> : <p className="empty-inline">暂无云函数执行记录</p>}
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
      {page === "cloud" ? renderCloud() : page === "messages" ? renderMessages() : page === "management" ? <GlobalManagement accounts={state.accounts || []} gameCatalog={state.gameCatalog || {}} onGoToAccountList={goToAccountList} /> : page === "today-detail" ? renderTodayDetail() : selectedId ? renderDetail() : renderHome()}
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
        <button className="choice-row" onClick={() => addAccount("hoyolab")}><span><strong>HoYoLAB（国际服）</strong><small>国际服游戏账号</small></span><ChevronRight size={17}/></button>
        <button className="choice-row" onClick={() => addAccount("miyoushe_cn")}><span><strong>米游社（国服）</strong><small>国服游戏账号</small></span><ChevronRight size={17}/></button>
        <button className="choice-row" onClick={() => addAccount("skland")}><span><strong>森空岛</strong><small>明日方舟 / 终末地</small></span><ChevronRight size={17}/></button>
      </section>
    </div>}

    {modal === "games" && <div className="modal-backdrop" onClick={closeModal}>
      <section className="modal-sheet game-sheet" onClick={e => e.stopPropagation()}>
        <div className="modal-title"><div><h2>选择要启用的游戏</h2><p>勾选需要参与自动签到的游戏。</p></div><button className="icon-button" onClick={closeModal} aria-label="关闭"><X size={18}/></button></div>
        <div className="game-options">{options.map((game: AnyState) => <label className="game-option" key={game.key}>
          <input type="checkbox" checked={(gameDraft || []).includes(game.key)} onChange={e => setGameDraft(old => e.target.checked ? [...(old || []),game.key] : (old || []).filter(x => x !== game.key))}/>
          <span>{game.label}</span><span className="checkmark"><Check size={14}/></span>
        </label>)}</div>
        <div className="modal-actions"><button className="secondary-button" onClick={closeModal}>取消</button><button className="primary-button" onClick={() => {setModal(null);notify("已选择；将在上传云函数时生效");}}>完成</button></div>
      </section>
    </div>}

    {toast && <div className="toast-message" role="status">{toast}</div>}
  </div>;
}
