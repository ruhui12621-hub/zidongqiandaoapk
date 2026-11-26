import { useState } from "react";
import { Boxes, Braces, ChevronRight, CircleUserRound, Gamepad2, Layers3, ShieldCheck } from "lucide-react";

type Props = {
  accounts: any[];
  gameCatalog: Record<string, any[]>;
  onGoToAccountList: () => void;
};

const gameModules = [
  { id: "hoyolab", label: "HoYoLAB", credential: "Cookie", catalog: "hoyolab_os", description: "国际服 Cookie 识别与账号身份解析" },
  { id: "miyoushe_cn", label: "米游社国服", credential: "Cookie", catalog: "hoyolab_cn", description: "国服 Cookie 识别与账号身份解析" },
  { id: "skland", label: "森空岛", credential: "Token", catalog: "skland", description: "独立 Token 提取、校验与加密保存" },
];

export default function GlobalManagement({ accounts, gameCatalog, onGoToAccountList }: Props) {
  const [section, setSection] = useState<"template" | "games" | "accounts">("template");
  const tabs = [
    { id: "template" as const, label: "模板", icon: Layers3 },
    { id: "games" as const, label: "游戏", icon: Gamepad2 },
    { id: "accounts" as const, label: "账号", icon: CircleUserRound },
  ];

  return <div className="global-management">
    <div className="page-heading">
      <h1>全局管理</h1>
      <p>统一管理通用模板、平台规则和账号关联。</p>
    </div>
    <div className="module-tabs" role="tablist" aria-label="全局管理模块">
      {tabs.map(tab => {
        const Icon = tab.icon;
        return <button key={tab.id} role="tab" aria-selected={section === tab.id}
          className={section === tab.id ? "active" : ""}
          onClick={() => setSection(tab.id)}>
          <Icon size={16}/><span>{tab.label}</span>
        </button>;
      })}
    </div>

    {section === "template" && <section className="surface-card module-card">
      <div className="module-card-heading">
        <span className="module-icon"><Layers3 size={19}/></span>
        <div><strong>通用签到模板</strong><small>全局唯一 · 所有平台共用</small></div>
        <span className="status-pill good">1 份</span>
      </div>
      <p className="module-description">模板只承载平台无关的通用能力。平台登录和凭据解析由游戏模块提供，账号只引用平台与游戏配置。</p>
      <div className="module-detail-list">
        {["账号注册与云端同步", "通用签到状态和结果展示", "华为云函数部署与配置更新", "日志组、日志流及运行消息管理"].map(item =>
          <div className="module-detail-row" key={item}><ShieldCheck size={15}/><span>{item}</span></div>
        )}
      </div>
      <div className="module-footnote">模板为系统公共实现，不会为每个平台复制一份。</div>
    </section>}

    {section === "games" && <div className="module-list">
      {gameModules.map(module => {
        const games = gameCatalog?.[module.catalog] || [];
        return <section className="surface-card module-card" key={module.id}>
          <div className="module-card-heading">
            <span className="module-icon"><Gamepad2 size={19}/></span>
            <div><strong>{module.label}</strong><small>{module.credential} · 平台独立规则</small></div>
            <span className="module-count">{games.length} 款游戏</span>
          </div>
          <p className="module-description">{module.description}</p>
          <div className="module-game-list">
            {games.length ? games.map(game => <span className="module-game-chip" key={game.key}>{game.label}</span>) :
              <span className="module-footnote">尚未读取游戏目录</span>}
          </div>
        </section>;
      })}
    </div>}

    {section === "accounts" && <div className="module-list">
      <section className="surface-card module-card account-schema-card">
        <div className="module-card-heading">
          <span className="module-icon"><Braces size={19}/></span>
          <div><strong>账号数据结构</strong><small>只保存关联信息，不保存凭据原文</small></div>
        </div>
        <div className="module-detail-list">
          {["应用内部唯一 ID", "所属游戏模块", "平台用户 ID", "启用的游戏 ID 列表"].map(item =>
            <div className="module-detail-row" key={item}><ShieldCheck size={15}/><span>{item}</span></div>
          )}
        </div>
      </section>
      <div className="module-section-label">{accounts.length} 个账号</div>
      {accounts.length ? accounts.map(account => {
        const module = gameModules.find(item => item.id === account.platform);
        const games = account.games || [];
        const labels = (gameCatalog?.[module?.catalog || ""] || []);
        return <button className="surface-card managed-account-row" key={account.id} onClick={onGoToAccountList}>
          <div className="managed-account-main">
            <strong>{account.name || module?.label || "账号"}</strong>
            <small>{module?.label || account.platform} · ID {String(account.id).slice(0, 8)}</small>
            <div className="module-game-list">
              {games.length ? games.map((key: string) => <span className="module-game-chip" key={key}>{labels.find((game: any) => game.key === key)?.label || key}</span>) :
                <span className="module-footnote">未启用游戏</span>}
            </div>
          </div>
          <ChevronRight size={17}/>
        </button>;
      }) : <div className="empty-state"><span className="empty-symbol"><Boxes size={25}/></span><strong>暂无账号</strong><p>读取云端账号后，会在这里统一显示账号与游戏关联。</p></div>}
    </div>}
  </div>;
}
