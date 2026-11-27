# 全局管理与平台 CSV 账号架构

应用继续采用“模板、平台、账号”三层结构。云端仍然保留原来的平台级 FunctionGraph 函数，不为每个账号创建新函数，也不引入 OBS 等额外存储服务。

## 1. 模板层

模板负责与平台无关的通用能力：

- CSV 账号表的同步、校验、编辑和保存
- 账号登录入口、账号状态和签到结果展示
- FunctionGraph 函数部署、ZIP 更新和版本检查
- LTS 日志组、日志流、运行消息及错误信息
- 通用登录 WebView 的视口、滚动和居中布局

React 首页是 CSV 表格编辑器。用户可以编辑账号名称、游戏选择、启用状态和备注；Cookie、Token 等凭据只显示是否已配置，不在表格中明文展示。登录成功后通过对应平台登录流程更新凭据，再保存 CSV。

## 2. 平台层与云函数

当前固定平台：

| 平台 ID | 平台 | 凭据 | 云端账号文件 |
| --- | --- | --- | --- |
| `hoyolab` | HoYoLAB 国际服 | Cookie | `config/accounts.csv` |
| `miyoushe_cn` | 米游社国服 | Cookie + stuid/stoken/mid | 与 HoYoLAB 共用 `config/accounts.csv` |
| `skland` | 森空岛 | Token | `code/accounts.csv` |

HoYoLAB 国际服和米游社国服由原来的 HoYoLAB 平台函数统一执行，因此共用一份 CSV；森空岛继续使用自己的平台函数和 CSV。每个平台函数仍然只部署一份，函数名保持原有配置。

平台适配器负责凭据解析、身份识别、游戏目录和 CSV/旧配置迁移：

- `HoYoLabCredentialAdapter`、`MiyousheCnCredentialAdapter`：解析 Cookie 和平台身份。
- `SklandCredentialAdapter`：解析森空岛 Token 身份。
- `HoYoLabCloudConfigAdapter`：读取旧 YAML、迁移旧账号，并保留非账号 YAML 设置。
- `SklandCloudConfigAdapter`：读取旧 JSON/Token 文件并迁移到 CSV。

## 3. CSV 数据格式

两个平台函数使用相同表头：

```csv
account_id,platform,name,user_id,credential,stuid,stoken,mid,games,enabled,remark
```

字段含义：

- `account_id`：应用生成的稳定 UUID，不随 Cookie/Token 更新而变化。
- `platform`：账号所属平台 ID。
- `name`：用户可编辑的账号名称。
- `user_id`：平台账号标识；森空岛当前使用 Token 的 SHA-256 标识。
- `credential`：HoYoLAB Cookie 或森空岛 Token。
- `stuid`、`stoken`、`mid`：米游社国服认证字段，其他账号留空。
- `games`：游戏 ID 使用竖线分隔，例如 `genshin|honkai2`。
- `enabled`：`true` 或 `false`，决定账号是否参与签到。
- `remark`：用户备注。

CSV 使用标准引号转义，支持逗号、引号和换行等 CSV 字符规则。当前限制为每个平台文件最多 100 行、2 MB。账号 UUID、平台 ID 和认证字段由应用管理，用户主要编辑名称、游戏、启用状态和备注。

## 4. 执行与隔离

平台函数读取自己的 CSV，按行遍历启用账号：

- 每个账号使用独立配置执行，不把一个账号的 Cookie/Token 传给另一个账号。
- 单个账号执行失败时记录该账号的结果，并继续处理后续账号。
- 禁用账号或没有选择游戏的账号不会发起签到请求。
- 日志包含稳定账号 UUID、平台账号标识和名称，不记录 Cookie/Token。
- HoYoLAB 账号级异常也会写入签到结果日志，便于 APK 查看失败原因。

## 5. 保存、冲突保护与迁移

FunctionGraph 的 ZIP 部署接口仍以完整 ZIP 为更新单位。因此，编辑一行时，APK 会读取当前 CSV、更新本地表格模型、重新生成完整 CSV，并将 CSV 放回 ZIP 后上传完整函数包；不会只上传 ZIP 内的单个文件。

为避免另一台设备同时编辑时覆盖其他账号，保存前会重新读取云端函数包并核对 CSV 快照：

- 云端 CSV 与上次同步快照一致，才继续上传。
- 如果其他设备新增、删除或修改了账号，应用停止上传并提示重新同步。
- HoYoLAB 还会核对去除凭据后的基础 YAML，避免覆盖其他云端设置。
- 旧格式迁移期间会核对旧配置，防止迁移时覆盖并发修改。

首次迁移时，HoYoLAB 从旧 `config/config.yaml` 导入账号到 CSV，并清空 YAML 中旧的 Cookie/stuid/stoken/mid；非账号配置保留。森空岛从旧 JSON/Token 文件迁移到 CSV，上传新包时移除旧 JSON 和 Token 文件。迁移完成后 CSV 是账号信息的唯一云端来源。

## 6. 安全与费用边界

- 不新增 OBS 桶、数据库、账号控制函数或其他存储服务。
- 继续使用现有平台 FunctionGraph 函数和现有 LTS 日志配置。
- CSV 位于 FunctionGraph 部署 ZIP 内，包含明文 Cookie/Token；其安全边界与原先 ZIP 中的 YAML/JSON 凭据相同，必须限制函数代码读取权限，不应公开或分享函数包。
- 森空岛 Token 在 APK 本地仍可保留一份加密的便捷副本；云端权威数据仍是 CSV。
- 保存前的冲突检查会额外下载一次当前函数 ZIP；这是为避免多设备覆盖，不引入新的云服务。

## 7. 约束

- 目前只注册 HoYoLAB、米游社国服和森空岛三个平台。
- 一个真实账号对应 CSV 中一行；一个平台函数可以管理该平台下多个账号。
- HoYoLAB 国际服与米游社国服共用 HoYoLAB 函数和 CSV，但每行保留独立平台标识与游戏配置。
- 账号只能选择所属平台支持的游戏。
- 账号表同步后会精确对齐云端 CSV，移除本地已不存在的旧行，避免已删除账号在下次保存时被重新上传。
