# cr-agent 设计

## 总图

```
IntelliJ + GitHub Copilot(Agent 模式)
   │ 读 .github/skills/*/SKILL.md 知道步骤;通过 mcp.json 拉起 run.bat,用 MCP 调工具
   ▼
cr-agent(一个 Java 进程,Spring Boot 3.5 + Spring AI MCP,stdio)
   ├── 变更单
   │     EndpointClient  ──► 你在 cr-agent.yml 里描述的接口(唯一碰 ServiceNow 的代码)
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

1. **不预设 ServiceNow 的形状。** 程序里没有表名、列名、路径。每个调用都是 `cr-agent.yml` 里的一条 endpoint 描述:方法、路径、query、body 模板、返回里记录的位置。接口变了改 yml。
2. **字段名不翻译。** 模板里写什么名字,草稿就是什么名字,直接发给接口。规则引擎按同样的名字查。少一层映射就少一处对不上。
3. **规则是文件。** 硬规则 yaml(必填、枚举、正则、长度、时间顺序、task 数量、窗口)每次校验重读;软规则 md 由 Copilot 判断。老板的要求落在文件里,有 changelog。
4. **每个事实有来源。** 草稿字段标注来自清单、模板、历史还是用户;没有来源的留空问人。
5. **写操作有闸。** 创建必须 `confirmed=true` 且硬规则无 error;只创建,提交审批是人。
6. **没有 mock。** 本地测试用单元测试(接口描述语言用本地 HttpServer 回放验证),真实数据只在公司。

## 变更单流程(create-cr)

```
用户一句话
 ├─ read_rules
 ├─ lookup_ci / lookup_service        Excel;带维护窗口,窗口外只提醒(HR-018 是 warn)
 ├─ list_templates                    按关键词选
 ├─ search_changes(有 search-changes 接口才做)   相似历史当范例
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

## 工具清单

| 组 | 工具 |
|---|---|
| ServiceNow | `sn_endpoints` `sn_call` `get_change` `search_changes` `create_change` |
| 清单 | `lookup_ci` `lookup_service` |
| 模板 / 校验 | `list_templates` `get_template` `build_draft` `validate_draft` `validate_change` |
| 规则与范例 | `read_rules` `add_hard_rule` `add_soft_rule` `save_example` `save_rejected` `list_examples` `read_example` |
| 驾驶舱 | `add_tasks` `list_tasks` `update_task` `task_notes` `get_day` `plan_day` `capture_note` `close_day` `save_knowledge` `read_knowledge` `search_knowledge` `task_history` `cockpit_url` |

## 日课驾驶舱

任务只来自你贴给 Copilot 的文本。数据在 `cockpit/`:`backlog.json`、`days/日期.json`、`task-notes/T-xxxx.md`、`knowledge/<ascii>.md`(主题名在首行)、`stats.json`。
三条流程:`morning-brief`(读昨天和知识 → 三件事 → 确认后 `plan_day`)、`capture`(`add_tasks` 自动合并相似;`capture_note` 分类;问过去只从文件答)、`evening-close`(统计 → 逐条确认 `save_knowledge` → `close_day`)。
`task_history` 把一个任务的全部记录拼起来,按时间排、关键字搜。页面和工具读写同一批文件。

## 代码结构

```
src/main/java/com/company/cragent/
├── config/        ServiceNowProperties(接口描述) InventoryProperties KnowledgeProperties CockpitProperties McpToolConfig
├── servicenow/    EndpointClient ServiceNowException
├── inventory/     Inventory(Excel/CSV)
├── template/      ChangeTemplate TemplateService
├── validation/    HardRule RuleEngine
├── model/         ChangeDraft(fields + tasks 的 map) CiInfo Violation
├── cockpit/       CockpitModel CockpitStore CockpitWebServer
└── tools/         ServiceNowTools InventoryTools TemplateTools ValidationTools KnowledgeTools CockpitTools
src/main/resources/static/cockpit.html   页面
src/test/...                             EndpointClientTest(本地 HttpServer)RuleEngineTest TemplateServiceTest InventoryTest CockpitStoreTest
```

## 已知限制

- 创建 task 和 CR 是不是同一个调用,取决于你的接口;分开的接口用 `sn_call` 再调一次,create-cr skill 里可补一步。
- 维护窗口规则是全局提醒(周日 00:00-06:00,warn),按服务器区分要在 hard-rules 里加 `when`。
- 页面「变更单」的中文字段标签只认标准名,其他字段按接口原名显示。
