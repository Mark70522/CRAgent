# cr-agent 设计

## 总图

```
IntelliJ + GitHub Copilot(Agent 模式)
   │ 读 .github/skills/*/SKILL.md 知道步骤;通过 mcp.json 拉起 run.bat,用 MCP 调工具
   ▼
cr-agent(一个 Java 进程,Spring Boot 3.5 + Spring AI MCP,stdio)
   ├── 变更单
   │     ServiceNowClient 接口(读 / 建 / 改三个方法)
   │       ├── YamlServiceNowClient   按 cr-agent.yml 里的 endpoints 描述调(默认)
   │       └── CompanyServiceNowClient 你自己实现,SnHttp 提供认证和 HTTP
   │     RecordCache          读/建/改过的每张 CR、ICE 单在 cockpit/records/ 留一份,页面直接看
   │     IceClient 接口(读 / 建 / 改三个方法,CR 在 ICE 里的登记)
   │       ├── YamlIceClient          ice: 段的 endpoints(默认)
   │       └── CompanyIceClient       你自己实现,IceHttp 同一套认证和 HTTP
   │     TemplateService     模板 → 草稿(字段名 = 你们接口的名字)
   │     RuleEngine          hard-rules.yaml 机器校验
   │     Inventory           Excel 服务器清单
   │     knowledge/          规则、模板、范例、打回案例
   └── 日课驾驶舱
         CockpitStore        cockpit/ 目录的 json / md 文件
         CockpitWebServer    127.0.0.1:7777 页面(JDK HttpServer)
```

分工:**Copilot 负责理解和写字,Java 负责查、算、存、把关;你的东西都是普通文件。**

## 原则

1. **不预设 ServiceNow 的形状,只预留三个动作。** 读(get-change)、建(create-change)、改(update-change),每个都是 `cr-agent.yml` 里的一条 endpoint 描述:方法、路径、query、body 模板、返回里记录的位置。程序里没有表名、列名、路径;接口变了改 yml。
2. **字段名只在边界翻译一次。** 模板、规则、范例、页面用标准名;接口叫什么由 `field-map` 说,`YamlServiceNowClient` / `YamlIceClient` 发出去改名、收回来改回。没有 `field-map` 就是原样。知识库永远不用跟着接口改。
3. **规则是文件。** 硬规则 yaml(必填、枚举、正则、长度、时间顺序、task 数量、窗口)每次校验重读;软规则 md 由 Copilot 判断。老板的要求落在文件里,有 changelog。
4. **每个事实有来源。** 草稿字段标注来自清单、模板、历史还是用户;没有来源的留空问人。
5. **写操作有闸。** 创建必须 `confirmed=true` 且硬规则无 error;只创建,提交审批是人。
6. **没有 mock 数据。** 主代码里没有任何假数据;单元测试用本地 HttpServer 回放和测试内的内存实现验证逻辑,真实数据只在公司。

## 变更单流程(create-cr)

```
用户一句话
 ├─ read_rules
 ├─ lookup_ci / lookup_service        Excel;带维护窗口,窗口外只提醒(HR-018 是 warn)
 ├─ list_templates                    按关键词选
 ├─ list_examples / read_example      归档的过审范例
 ├─ build_draft                       模板默认字段 + 标题 + 描述章节骨架(TODO)+ task 时间线 + 窗口
 ├─ Copilot 填 TODO
 ├─ validate_draft ⟲                  硬规则,error 清零
 ├─ 软规则自审
 ├─ 展示草稿 + 字段来源 → 用户确认
 └─ create_change(confirmed=true)     → EndpointClient.call("create-change", {fields, tasks, ...字段})
```

create-change 的 body:不写模板 = 草稿字段平铺;写模板可用 `${fields}`、`${tasks}` 或任意单个字段。

## 接口描述语言(EndpointClient)

| 键 | 作用 |
|---|---|
| `method` / `path` | `${name}` 按文本替换并 URL 编码 |
| `query` | 每项 `${name}`,值为空的参数不发 |
| `headers` | 每接口额外头,也可带占位符 |
| `body` | POST/PUT/PATCH:无 = 参数 map 转 JSON;有 = 模板,`${name}` 按 JSON 值替换,渲染后必须是合法 JSON |
| `result` | 点路径取记录;取不到报错并附返回开头 |
| `tasks` | 记录内 task 列表的点路径(页面和校验用) |

认证:`bearer`(`token-env`)、`basic`(`user` + `password-env`)、`none`;全局 `headers`;代理;超时。错误信息带 HTTP 状态和返回开头,不吞。

## 让它可控的三件事

| 机制 | 在哪 | 做什么 |
|---|---|---|
| 规则回归 | `knowledge/Regression` + `RegressionTest` + 工具 `eval_rules` | 过审范例必须 0 个 error,打回范例必须命中规则(含 `expected_rules`)。`mvn test` 把它当闸门;`add_hard_rule` / `save_*` 自动跑并返回结果 |
| 审计日志 | `audit/AuditLog` + `AuditedToolCallback` | 每个 MCP 工具调用和页面 POST 一行 JSON 到 `logs/audit.jsonl`:时间、来源、动作、参数(截断)、成败、耗时 |
| 授权分级 | README 第 5 节 | 只读工具在 Copilot 里 Always allow;写工具保留确认,且代码里 `confirmed=true` 再拦一道 |

范例格式是 YAML(`ExampleStore`),同一份文件既给 Copilot 当写作范例,也给回归当测试用例。

## 工具清单

| 组 | 工具 |
|---|---|
| 状态 | `status`(接口、配置体检、清单、规则、范例、回归、页面、审计一次看全) |
| 接入 | `check_config`(yml 体检)`probe`(渲染 / 真发一个端点,看完整返回)`save_fixture`(真实返回存成回放样本,`FixtureReplayTest` 验证 yml) |
| ServiceNow | `get_change` `create_change` `update_change`(建和改都要 confirmed=true)· task 三个独立接口 `create_task` `cancel_task` `close_task` |
| ICE | `draft_ice`(按 `ice.from-change` 从 CR 算出 ICE 字段)`get_ice` `create_ice` `update_ice` `ice_score`(建改要 confirmed=true;`ice:` 段没配就拒绝) |
| React UI | `/api/v1`(`{code,message,data}`)+ `/app/`:变更单、task、ICE、分数、台账、字段目录(`knowledge/forms/*.json`,FormCatalog) |
| 清单 | `lookup_ci` `lookup_service` |
| 模板 / 校验 | `list_templates` `get_template` `build_draft` `validate_draft` `validate_change` |
| 规则与范例 | `read_rules` `add_hard_rule` `add_soft_rule` `save_example` `save_rejected` `list_examples` `read_example` `eval_rules` |
| 驾驶舱 | `add_tasks` `list_tasks` `update_task` `task_notes` `get_day` `plan_day` `capture_note` `close_day` `save_knowledge` `read_knowledge` `search_knowledge` `task_history` `cockpit_url` |

## 日课驾驶舱

任务只来自你贴给 Copilot 的文本。数据在 `cockpit/`(gitignore,个人数据):`backlog.json`、`days/日期.json`、`task-notes/T-xxxx.md`、`knowledge/<ascii>.md`(主题名在首行)、`stats.json`。
三条流程:`morning-brief`(读昨天和知识 → 三件事 + 盯着的清单 → 确认后 `plan_day`)、`capture`(`add_tasks` 自动合并相似并返回以前做过的同类任务;`capture_note` 分类;问过去只从文件答)、`evening-close`(统计 → 逐条确认 `save_knowledge` → 周期任务滚到下一期 → `close_day`)。
`task_history` 把一个任务的全部记录拼起来,按时间排、关键字搜。页面和工具读写同一批文件。

任务的生命周期按运维的实际走:

| 字段 / 状态 | 含义 |
|---|---|
| `todo` / `doing` | 今天能做的;早安只从这里挑三件事 |
| `waiting` + `waitingOn` | 在等审批、回复、窗口;算未完成但不算拖延,早安单独列出 |
| `scheduledAt` | 真正执行的时间(维护窗口),和 `due`(必须完成)分开;`get_day.attention` 给出 7 天内要执行的和已过时未完成的 |
| `cr` | 挂着的变更单号;`create_change(taskId=…)` 自动回填,`get_change` 反向带出任务;有 CR 的任务要 CR 关了才算完 |
| `est` + `estBy` | 工时只记用户说的;Copilot 猜的标 `ai`,页面显示「AI 估」 |
| `repeat` | 每月 / 每周 / 每季度;做完时提议建下一期 |

每个事实有来源这条原则也适用于任务:文本里没有的工时、日期不填,别人的任务先问。

## 代码结构

```
src/main/java/com/company/cragent/
├── config/        ServiceNowProperties(接口描述) InventoryProperties KnowledgeProperties CockpitProperties McpToolConfig(工具注册 + 审计包装)
├── audit/         AuditLog AuditedToolCallback
├── knowledge/     ExampleStore(YAML 范例) Regression(规则回归)
├── servicenow/    ServiceNowClient(接口) YamlServiceNowClient CompanyServiceNowClient(你填) SnHttp(认证/HTTP/JSON) EndpointClient(yml 描述执行器) ServiceNowException
├── ice/           IceClient(接口) YamlIceClient CompanyIceClient(你填) IceHttp IceEndpoints(ice: 段上的同一套 SnHttp / EndpointClient) IceRecord
├── inventory/     Inventory(Excel/CSV)
├── template/      ChangeTemplate TemplateService
├── validation/    HardRule RuleEngine
├── model/         ChangeDraft(fields + tasks 的 map) CiInfo Violation
├── cockpit/       CockpitModel CockpitStore CockpitWebServer
└── tools/         StatusTools ServiceNowTools InventoryTools TemplateTools ValidationTools KnowledgeTools CockpitTools
src/main/resources/static/cockpit.html   页面
src/test/...                             EndpointClientTest(本地 HttpServer)RuleEngineTest TemplateServiceTest InventoryTest CockpitStoreTest
```

## 已知限制

- 创建 task 和 CR 是不是同一个调用,取决于你的接口;分开的话 create-change 的 body 模板里不放 `${tasks}`,再加一个接口时在 ServiceNowTools 里加一个方法。
- 维护窗口规则是全局提醒(周日 00:00-06:00,warn),按服务器区分要在 hard-rules 里加 `when`。
- 页面「变更单」的中文字段标签只认标准名,其他字段按接口原名显示。
- 本地留存只在工具被调用时更新,不会后台轮询接口;看最新状态要点「从接口刷新」。留存不自动清理,文件可直接删。
