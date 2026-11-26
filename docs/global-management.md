# 全局管理模块

应用的账号签到系统按三个层次组织，所有平台共用一份模板。当前平台固定为 HoYoLAB、米游社国服和森空岛；全局管理页面不提供动态新增或删除平台的功能。

## 1. 模板

```text
Template: default (唯一)
```

模板负责与平台无关的通用能力：

- 账号注册、账号状态和云端配置同步
- 通用签到执行流程与结果展示
- 华为云函数部署、配置更新和检查
- 日志组、日志流、运行消息及错误信息
- 登录 WebView 的通用视口、滚动和居中布局

`TemplateLoginLayout` 是统一登录页面呈现入口。森空岛原有的特殊纵向居中偏移仍由模板按平台 ID 选择，不由森空岛平台实现。森空岛自动打开网站登录入口则属于平台专有行为，由 `SklandLoginAdapter` 负责。

## 2. 游戏模块

游戏模块使用稳定的平台 ID，集中定义凭据类型、解析规则和允许启用的游戏：

| 模块 ID | 平台 | 凭据 | 游戏目录 |
| --- | --- | --- | --- |
| `hoyolab` | HoYoLAB 国际服 | Cookie | `GameCatalog.hoyolabOs` |
| `miyoushe_cn` | 米游社国服 | Cookie | `GameCatalog.hoyolabCn` |
| `skland` | 森空岛 | Token | `GameCatalog.skland` |

规则入口位于 `GlobalModuleRegistry`。平台登录凭据解析由独立适配器提供：

- `HoYoLabCredentialAdapter`：国际服 Cookie 认证字段和身份提取规则。
- `MiyousheCnCredentialAdapter`：国服 Cookie 认证字段和身份提取规则。
- `SklandCredentialAdapter`：森空岛 Token 校验。
- `HoYoLabCloudConfigAdapter`：HoYoLAB 云端 YAML 的读取、Cookie 更新和游戏开关更新。
- `SklandCloudConfigAdapter`：森空岛账号配置的读取、旧格式迁移、序列化和游戏选择。

登录 Activity 负责承载网页和会话生命周期，不再自行维护平台凭据字段集合。通用云端部署流程由模板协调，平台配置结构由对应平台适配器处理。

## 3. 账号模块

账号基础关联由以下字段组成：

- `id`：应用内部生成的稳定 UUID，不随 Cookie/Token 更新而变化
- `platform`：所属游戏模块 ID
- `platformUserId`：平台侧身份 ID，用于识别和去重，不作为本地关联主键
- `games`：该账号启用的游戏 ID 集合

Cookie 与 Token 不属于账号基础数据。它们继续按内部账号 UUID 存入独立的加密凭据保管箱；云端部署时才由模板与对应平台适配器读取和合并。

## 4. 森空岛配置格式

新版森空岛云函数使用 `code/INPUT_HYPERGRYPH_ACCOUNTS.json`，格式版本为 1：

```json
{
  "version": 1,
  "accounts": [
    {
      "platformUserId": "Token 的 SHA-256 标识",
      "token": "实际 Token",
      "games": ["arknights", "endfield"]
    }
  ]
}
```

每个账号拥有独立的游戏选择。云函数先按账号配置筛选角色，再执行对应游戏的签到；未启用的游戏不会执行。签到结果按游戏名称写入日志。

兼容规则：

- 旧版函数包只有 `code/INPUT_HYPERGRYPH_TOKEN.txt` 时，读取旧 Token 列表，并按旧行为默认启用全部森空岛游戏。
- 新版部署同时更新 JSON 账号配置和旧版 Token 文件，便于旧版本回退时继续读取 Token。
- 新版 JSON 配置一旦存在且有效，就以 JSON 为权威来源；格式损坏时停止执行，不静默回退到旧文件。
- 部署时从已同步的账号列表和各账号独立加密保管箱重新组装完整账号清单，不从 APK 空白模板覆盖云端其他账号。

## 5. 约束

- 当前只注册上述三个平台；界面没有新增平台入口。
- 账号选择的游戏必须属于其所属平台的游戏目录。
- 不允许把一个平台的游戏 ID 写入另一个平台的账号。
- 不允许把 Cookie、Token 或 AK/SK 写入普通账号展示数据或运行日志。
- 账号配置仍以华为云函数配置为权威来源；本地账号缓存不取代云端数据。
- 将来新增平台必须先实现真实的登录、凭据解析、身份识别、游戏目录和签到适配，再由开发者注册；不能只创建一个空壳平台条目。

## 6. 实现边界

模板层仍负责通用页面、账号协作、FunctionGraph 部署编排和 LTS 日志隔离；平台层负责平台专属凭据和云函数配置格式；账号层只保存平台关联和游戏选择。

森空岛游戏选择已接入账号详情、原生桥接、账号配置序列化、云端导入和云函数角色筛选，不再只是本地 UI 勾选。云端 Python 构建流程会验证结构化配置、旧 Token 文件兼容以及空配置保护。
