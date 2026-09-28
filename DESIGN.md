# cr-agent 设计说明

README 讲怎么用,这里讲为什么这样设计、每一块具体怎么工作。

## 目录

1. [总体架构](#1-总体架构)
2. [设计原则](#2-设计原则)
3. [一次创建的完整数据流](#3-一次创建的完整数据流)
4. [代码结构](#4-代码结构)
5. [配置](#5-配置)
6. [知识层:规则、模板、范例](#6-知识层规则模板范例)
7. [三条流程(skills)](#7-三条流程skills)
8. [学习闭环](#8-学习闭环)
9. [安全闸](#9-安全闸)
10. [工具清单](#10-工具清单)
11. [ServiceNow 接口依赖](#11-servicenow-接口依赖)
12. [扩展点](#12-扩展点)
13. [已知限制](#13-已知限制)

---

## 1. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│  VS Code + GitHub Copilot (Agent 模式)                         │
│  理解自然语言, 选 skill, 编排工具调用, 写描述文字, 给人确认         │
└───────────────────────────┬──────────────────────────────────┘
                            │ 读 skills / 调用 MCP tools (stdio, JSON-RPC)
┌───────────────────────────▼──────────────────────────────────┐
│  Skills  .github/skills/*/SKILL.md                 纯 markdown │
│  create-cr  review-cr  learn-rules  sync-history               │
│  定义"先查什么、再查什么、什么情况下停下来问人"                     │
└───────────────────────────┬──────────────────────────────────┘
                            │ 引用
┌───────────────────────────▼──────────────────────────────────┐
│  知识层  knowledge/                             纯文件, 进 git   │
│  rules/     硬规则 yaml + 软规则 md + changelog                  │
│  templates/ 每类变更: 默认字段 + task 序列与时长 + 描述章节         │
│  examples/  过审的金标准 CR        rejected/ 被打回的 CR + 理由    │
└───────────────────────────┬──────────────────────────────────┘
                            │ 读写
┌───────────────────────────▼──────────────────────────────────┐
│  MCP Server  Java 17 / Spring Boot 3.5 / Spring AI 1.1          │
│  tools/       23 个 @Tool 方法, 只做参数转换                      │
│  validation/  硬规则引擎        template/  模板展开 + 时间推算     │
│  history/     SQLite 历史库     inventory/ Excel 服务器清单       │
│  servicenow/  REST 客户端 / mock                                 │
│  配置: cr-agent.yml (实例、账号、Excel 列名)                       │
└─────────────┬─────────────────────────────┬──────────────────┘
              │ 读文件                        │ HTTPS Table API, 服务账号
┌─────────────▼──────────────┐ ┌────────────▼──────────────────┐
│  knowledge/inventory.xlsx  │ │  ServiceNow                    │
│  service / 环境 / server    │ │  change_request  change_task   │
│  (替代 CMDB 查询)            │ │  sysapproval_approver          │
│                            │ │  sys_journal_field             │
└────────────────────────────┘ └────────────────────────────────┘
```

| 层 | 负责 | 不负责 |
|---|---|---|
| Copilot | 理解话、写文字、判断软规则、跟人对话 | 记规则(每次从文件读)、直接碰 ServiceNow |
| Skills | 步骤顺序、什么时候停下来问 | 任何计算 |
| 知识层 | 规则、模板、范例,"老板的要求"全在这 | 代码 |
| MCP Server | 查数据、算时间、硬校验、写 ServiceNow | 生成文字、做判断 |

Copilot 和 Java 程序之间走 MCP(Model Context Protocol):VS Code 按 `.vscode/mcp.json` 拉起 `java -jar`,通过 stdin/stdout 传 JSON-RPC。Copilot 启动时问一次"你有哪些工具",之后每次需要就调。

## 2. 设计原则

**规则是文件,不是模型。** 老板说一条新规则,改一个文件就生效:不改代码、不重启、不训练。规则文件进 git,谁改的、为什么改在 `changelog.md` 里。

**硬规则和软规则分开。** 能写成代码的(必填、枚举、正则、长度、时间顺序、task 数量)交给规则引擎,永远不会漏。需要判断的(描述够不够详细、影响说明够不够具体)交给 Copilot 按 `soft-rules.md` 逐条审。一条规则先落软规则,跑稳了、能精确表达了再升硬规则。

**每个事实都有来源。** 草稿里每个字段要么来自用户的话、要么来自 CMDB、要么来自模板、要么来自相似历史 CR。`build_draft` 返回时标注每个字段来源,Copilot 给人看草稿时也要列出来。没有来源的东西(补丁编号、ticket 号、业务方确认)必须留空问人,不许编。

**模板定结构,模型填内容。** 描述的章节、task 序列和时长由模板固定,Copilot 只往章节里填内容。输出的稳定性来自模板,质量来自范例。

**人在回路里,写操作有闸。** 创建前必须把完整草稿给人看并明确确认;只创建到 New 状态,提交审批由人操作。用 `confirmed` 参数和硬规则双重把关,不依赖 Copilot 自觉。

**能离线的都离线。** 历史 CR 同步到本地 SQLite,检索和统计不打实例;mock 模式让整条流程不连公司网络也能开发和演示。

**公司相关的东西只在一个文件里。** 实例地址、账号、CMDB 列名、状态值、代理全在 `cr-agent.yml`,Java 代码里没有任何公司特有的字符串。

## 3. 一次创建的完整数据流

用户:"给 srv-app-01 和 srv-app-02 打 10 月 Windows 补丁,10 月 10 日 02:00 开始"

```
Copilot                          MCP Server                        ServiceNow / 文件
  │
  │ 读 create-cr/SKILL.md
  │
  ├─ read_rules ─────────────────► 读 hard-rules.yaml + soft-rules.md
  │◄──────── 17 条硬规则 + 软规则文本
  │
  ├─ lookup_ci("srv-app-01, srv-app-02") ─► 读 knowledge/inventory.xlsx (Excel 服务器清单)
  │◄──────── 两台: prod, Order Portal, Win2019, Wintel Ops, 窗口 Sun 00:00-06:00
  │           (用户说的是服务名时改调 lookup_service("Order Portal", "prod") 展开成服务器)
  │
  ├─ list_templates ─────────────► 读 templates/*.yaml
  │◄──────── os-patch (关键词 patch/补丁/windows ...)
  │
  ├─ find_similar_changes(ci=srv-app, keyword=patch) ─► SELECT ... FROM history.db
  │◄──────── CHG0030001 等 3 个过审 CR, 含描述和 task
  │
  ├─ get_change_windows("srv-app-01") ─► Excel 里该行的窗口, 没填就用默认 Sun 00:00-06:00
  │◄──────── Sun 00:00-06:00
  │           (10-11 是周日 01:00, 在默认窗口内; 不在的话 HR-018 会给 warn, Copilot 问用户确认)
  │
  ├─ build_draft(os-patch, CIs, "2026-10-11 01:00:00", "2026-10 Windows monthly patches")
  │                               ► 模板 fields → 草稿默认值
  │                               ► 4 个 task: 01:00-01:30 / 01:30-03:30 / 03:30-04:15 / 04:15-04:30
  │                               ► 窗口 01:00-05:00 (取 default_duration 240 分钟)
  │                               ► description = 6 个章节, 每节 <TODO: hint>
  │◄──────── 草稿 + fieldSources + notes
  │
  │ (Copilot 自己) 参考范例把 6 个 TODO 写成具体内容
  │
  ├─ validate_draft(草稿) ───────► 逐条跑 hard-rules.yaml
  │◄──────── passed=false: HR-003 还有 TODO / HR-002 描述太短 / HR-018 不在周日窗口 ...
  │ (改) ─► validate_draft ─► passed=true
  │
  │ (Copilot 自己) 逐条对软规则: 影响范围有没有写时长? 回退有没有写耗时?
  │
  │ 给用户看: 字段表 + task 时间线 + 来源表 ("risk 来自模板", "group 来自 CMDB", ...)
  │◄──────── 用户: "创建"
  │
  ├─ create_change(草稿, confirmed=true) ─► 再跑一次硬规则 (error 就拒绝)
  │                                       ► POST change_request  ─► CHG0031234
  │                                       ► POST change_task ×4   ─► CTASK...
  │◄──────── number, url, taskNumbers
  │
  └─ 回复用户: CHG 号 + 链接, 提醒去提交审批
```

## 4. 代码结构

```
src/main/java/com/company/cragent/
├── CrAgentApplication.java          Spring Boot 入口
├── config/
│   ├── ServiceNowProperties.java    servicenow.* (来自 cr-agent.yml), 含代理
│   ├── InventoryProperties.java     inventory.* : Excel 路径、sheet、表头映射、默认窗口
│   ├── KnowledgeProperties.java     cr.knowledge-dir 及子目录
│   └── McpToolConfig.java           把 tools/ 下的 @Tool 注册成 MCP 工具
├── inventory/
│   ├── CiDirectory.java             接口: 查服务器 / 按服务展开 / 窗口
│   ├── ExcelCiDirectory.java        读 .xlsx 或 .csv (inventory.file 非空时启用), 文件改了自动重读
│   └── ServiceNowCiDirectory.java   退回查 CMDB (inventory.file 留空时启用)
├── model/                           records: ChangeDraft, TaskDraft, ChangeRecord, CiInfo,
│                                    CreatedChange, MaintenanceWindow, Violation
├── servicenow/
│   ├── ServiceNowGateway.java       接口: agent 需要的全部 SN 操作
│   ├── RestServiceNowClient.java    Table API 实现  (servicenow.mock=false)
│   └── MockServiceNowClient.java    内存实现        (servicenow.mock=true)
├── validation/
│   ├── HardRule.java                hard-rules.yaml 的一条
│   └── RuleEngine.java              规则引擎, 每次校验重新读文件
├── template/
│   ├── ChangeTemplate.java          templates/*.yaml 的结构
│   └── TemplateService.java         加载 + 展开成 ChangeDraft(含 task 时间线)
├── history/
│   └── HistoryStore.java            SQLite: upsert / findSimilar / findRejected / fieldStats
└── tools/                           每类一组 @Tool, 只做参数转换和组合
    ├── CmdbTools.java               lookup_ci, lookup_service (走 CiDirectory)
    ├── ChangeTools.java             get_change, find_rejected_changes, get_change_windows,
    │                                create_change, add_change_tasks, update_change
    ├── TemplateTools.java           list_templates, get_template, build_draft
    ├── ValidationTools.java         validate_draft, validate_change
    ├── HistoryTools.java            sync_history, find_similar_changes, history_field_stats
    └── KnowledgeTools.java          read_rules, add_hard_rule, add_soft_rule,
                                     save_example, save_rejected, list_examples, read_example

src/main/resources/
├── application.yml                  内部默认值 + import ./cr-agent.yml
├── logback-spring.xml               只写文件 (stdout 是协议通道)
└── schema.sql                       change_history 表

src/test/java/...                    RuleEngineTest, TemplateServiceTest
scripts/smoke_test.py                stdio 端到端测试 (mock, 用 target/ 下的副本)
scripts/encoding_check.py            验证 stdout 是 UTF-8
```

关键技术点:

- **stdio 传输**:stdout 只能有 JSON-RPC。所以 banner 关掉、console 日志关掉,只留文件日志(`logback-spring.xml`)。这是 stdio MCP server 最容易踩的坑。
- **`ServiceNowGateway` 接口 + 两个实现**:`@ConditionalOnProperty(servicenow.mock)` 切换,上层不知道对面是 mock 还是真实例。
- **`ChangeDraft` 是 record**:Spring AI 从 record 自动生成 JSON schema 给 Copilot,字段名和 `@ToolParam` 描述就是 Copilot 看到的"表单"。自定义字段走 `extra` map。
- **规则文件每次重读**:`RuleEngine.loadRules()` 每次校验都读 yaml,几毫秒换来改完即生效。
- **写 ServiceNow 用 `sysparm_input_display_value=true`**:reference 字段(cmdb_ci、assignment_group)直接传显示名,不用先查 sys_id。
- **配置分层**:`application.yml` 是内部默认值 → `./cr-agent.yml`(工作区)→ `%USERPROFILE%\.cr-agent\cr-agent.yml` 或 `CR_AGENT_CONFIG` 指的文件(工作区外)→ 环境变量 → 命令行参数,后者覆盖前者。smoke test 用命令行参数保证永远 mock。

## 5. 配置

| 文件 | 谁改 | 内容 |
|---|---|---|
| `knowledge/inventory.xlsx` | **你** | 服务器清单:服务 / 环境 / 服务器,可选 IP、OS、负责组、窗口 |
| `cr-agent.yml` | **你** | Excel 路径和表头映射、实例、账号名、代理。不进 git |
| `%USERPROFILE%\.cr-agent\cr-agent.yml`(或 `CR_AGENT_CONFIG` 指的文件) | **你** | 工作区外的配置,同格式,放密码。覆盖工作区文件 |
| 环境变量 `SERVICENOW_PASSWORD` / `SERVICENOW_TOKEN` | **你** | 密码的另一种放法。覆盖所有文件 |
| `cr-agent.example.yml` | 参考 | 上面文件的完整注释版 |
| `.vscode/mcp.json` | 基本不用改 | 只写"用 java 跑 target/cr-agent.jar,工作目录是项目根" |
| `application.yml` | 不改 | 内部默认值 |

`cr-agent.yml` 的字段:

| 键 | 默认 | 说明 |
|---|---|---|
| `inventory.file` | ./knowledge/inventory.xlsx | 服务器清单,.xlsx 或 .csv;留空则查 CMDB |
| `inventory.sheet` | 第一个 | sheet 名 |
| `inventory.columns.*` | service, environment, server, ip, os, owner_group, maintenance_window | 表头映射,不分大小写和空格 |
| `inventory.maintenance-window` | Sun 00:00-06:00 | 表里没填窗口的服务器显示这个(真正的检查在 HR-018) |
| `servicenow.mock` | true | false 走真实例 |
| `servicenow.instance` | | 实例 URL |
| `servicenow.user` | | Basic 认证的账号名 |
| `servicenow.password` | | 不写在工作区文件里:外部配置文件或环境变量 `SERVICENOW_PASSWORD` |
| `servicenow.token` | | Bearer token,设了优先;外部配置文件或环境变量 `SERVICENOW_TOKEN` |
| `servicenow.ci-table` | cmdb_ci_server | 查服务器的表 |
| `servicenow.ci-fields.*` | 见 example | CMDB 列名:name, ip-address, os, environment, owner-group, business-app, maintenance-schedule |
| `servicenow.closed-states` | 3,4,7,-4 | 同步历史时的状态过滤 |
| `servicenow.proxy-host` / `proxy-port` | | HTTP 代理 |

## 6. 知识层:规则、模板、范例

```
knowledge/
├── rules/
│   ├── hard-rules.yaml     机器校验
│   ├── soft-rules.md       Copilot 判断
│   └── changelog.md        每条规则: 日期 | hard/soft | id | 摘要 | 谁提的为什么
├── templates/
│   ├── os-patch.yaml
│   ├── db-patch.yaml
│   └── app-release.yaml
├── examples/<模板名>/CHGxxx.md    save_example 生成
└── rejected/CHGxxx.md             save_rejected 生成, 含打回理由
```

### 硬规则

```yaml
rules:
  - id: HR-011
    description: Backout plan is required for normal and emergency changes
    type: required_if
    field: backout_plan
    when: { field: type, in: [normal, emergency] }
    severity: error            # error 阻止创建 | warn 只报告, 默认 error
    suggestion: State how to roll back and how long it takes
```

| type | 检查 | 用到的键 |
|---|---|---|
| `required` / `required_if` | 字段非空 | field, (when) |
| `min_length` / `max_length` | 字段长度 | field, min / max |
| `enum` | 值在列表内(不分大小写) | field, values |
| `regex` | 整体匹配正则 | field, pattern |
| `forbidden_words` | 不含指定文本 | field, values |
| `date_order` | start_date < end_date | 无 |
| `tasks_min` | task 数量 ≥ min | min |
| `tasks_within_window` | 每个 task 在 CR 窗口内 | 无 |
| `tasks_sequential` | task 按 order 排列不重叠 | 无 |
| `tasks_field_required` | 每个 task 的某字段非空 | field |
| `maintenance_window` | start/end 在指定星期几的 from-to 之间,且同一天 | days, from, to |

`when` 支持 `equals` / `not_equals` / `contains` / `in` / `blank`。

HR-018 就是 `maintenance_window`,`days: [SUNDAY]`、`from: "00:00"`、`to: "06:00"`,`severity: warn`:窗口会前后浮动、由用户决定,所以只提醒不拦截。默认窗口变了改那三行;想改成硬拦截把 severity 改成 error。

### 模板

```yaml
name: os-patch
description: Operating system patching
match_keywords: [patch, 补丁, windows update, kb, yum]     # Copilot 用来选模板
fields:                              # change_request 默认值, u_* 字段也放这
  type: normal
  category: Software
  risk: Moderate
  backout_plan: "Restore the VM snapshot taken in the pre-check task (approx. 20 minutes)."
short_description_pattern: "[PATCH] {ci_list} - {summary}"
default_duration_minutes: 240
tasks:                               # 按 duration 首尾相接从 plannedStart 推时间
  - order: 10
    short_description: "Pre-check and snapshot"
    duration_minutes: 30
    assignment_group: "{ci_owner_group}"
description_sections:                # 生成 description 骨架, 每节一个 <TODO: hint>
  - heading: 变更对象
    hint: 每台服务器的名称、环境、OS、所属应用
```

可用变量:`{ci_list}` `{ci_owner_group}` `{environment}` `{summary}`。

### 范例

`save_example` 把一个 CR 渲染成 markdown(字段、描述各段、task 时间线、审批记录)存进 `examples/<分类>/`。Copilot 写描述时读同类的 3 到 5 个。少而精,多了反而乱。

## 7. 三条流程(skills)

### create-cr

见第 3 节的数据流。要点:CI 查不到就停下问;时间不在窗口内就提议最近合法时段而不是默默改;硬规则最多循环 3 次;给人看草稿时列字段来源。

### review-cr

```
CHG 号 或 粘贴的草稿
   ├─ read_rules
   ├─ get_change                   完整字段 + tasks + 审批记录 + journal
   ├─ find_similar_changes         2 个过审范例, 对比详细程度
   ├─ validate_change              硬规则
   ├─ (Copilot) 逐条软规则 + 和范例比缺什么
   ├─ 输出表格: 规则 id | 严重度 | 字段 | 问题 | 具体替换文本
   └─ 用户说"改" → update_change / add_change_tasks → 再 validate_change
```

用户说"被打回了":`save_rejected` 存档 → 每条理由对照现有规则 → 没覆盖的提议新规则 → 确认后 `add_hard_rule` / `add_soft_rule` → 修 CR。

### learn-rules

| 入口 | 来源 | 动作 |
|---|---|---|
| A. 老板口头说了一条 | 用户转述 | 精确复述 → 判断硬/软 → 查重 → 确认后写入 |
| B. 挖打回记录 | `find_rejected_changes` + `rejected/` | 拆每条理由 → 按频率归组 → 排除已覆盖 → 排序提议 |
| C. 挖过审共性 | `find_similar_changes` 每类 10 个 | 标题格式、章节、必填字段、task 序列 → 提议模板调整和软规则 → `save_example` |

质量要求:一条规则一个检查;加完硬规则后拿几个过审范例跑 `validate_change`,误报就删或降 warn。

## 8. 学习闭环

```
                 ┌──────────────────────────┐
                 │  老板 / CAB 提出新要求      │
                 └────────────┬─────────────┘
                              │ 口头 或 打回 CR
                              ▼
   ┌───────────────── learn-rules skill ─────────────────┐
   │ 提炼 → 判断硬/软 → 查重 → 人确认 → 写入 rules/ + changelog │
   └───────────────────────┬─────────────────────────────┘
                           │ 下一次起
                           ▼
   ┌────── create-cr ──────┐      ┌────── review-cr ──────┐
   │ 草稿自动遵守新规则       │      │ 存量 CR 按新规则批量查   │
   └───────────┬───────────┘      └───────────┬───────────┘
               │ 过审                          │ 打回
               ▼                              ▼
       save_example                    save_rejected
       examples/ 变多, 范例更准          rejected/ 变多, 回到顶部
```

"学习"= 规则和范例的积累,不是模型训练。可解释、可审计、可回滚:任何规则都能在 changelog 找到出处,误加了删掉即可。

## 9. 安全闸

| 闸 | 在哪 | 防什么 |
|---|---|---|
| `confirmed=true` 才创建 | `ChangeTools.createChange` | Copilot 没给人看就建 |
| 硬规则有 error 就拒绝创建 | 同上 | 不合规的 CR 进系统 |
| 只创建到 New,不提交审批 | 代码里不调 approval 接口 | 自动进 CAB 没人兜底 |
| 先只读权限,后加写权限 | 服务账号 ACL | 调试阶段误写 |
| 事实必须有来源 | `copilot-instructions.md` + `build_draft` 的 fieldSources | 编造补丁号、ticket 号 |
| CI 查不到就停 | create-cr skill 第 2 步 | 写错服务器 |
| `add_hard_rule` 先解析再写入,id 查重 | `KnowledgeTools` | 写坏规则文件 |
| `read_example` 路径限制在 knowledge/ 内 | `KnowledgeTools` | 读任意文件 |
| 凭据不在工作区任何文件 | 环境变量或 `%USERPROFILE%\.cr-agent\cr-agent.yml` | Copilot 读到、进 git |

## 10. 工具清单

| 工具 | 输入 | 输出 | 说明 |
|---|---|---|---|
| `lookup_ci` | 名字/IP,逗号分隔 | 每个查询的服务器列表 | 环境、OS、负责组、所属服务、维护窗口,来自 Excel |
| `lookup_service` | 服务名、环境(可选) | 该服务的服务器列表 | 不传服务名则列出所有服务 |
| `get_change` | CHG 号 | 完整记录 | 含 tasks、审批、journal |
| `find_similar_changes` | ci / type / keyword / approvedOnly / limit | 摘要列表 | 本地库,空则回退实例查询 |
| `find_rejected_changes` | sinceDate / limit | 打回列表 | 含审批人备注 |
| `history_field_stats` | ci / type / column | 值分布 | 过审 CR 的字段众数 |
| `get_change_windows` | 服务器名 | 窗口列表 | Excel 行里的窗口,没填则默认 Sun 00:00-06:00 |
| `list_templates` / `get_template` | 模板名 | 模板 | |
| `build_draft` | 模板名、CI 列表、开始时间、摘要 | 草稿 + 字段来源 + 备注 | task 时间自动推算 |
| `validate_draft` | 草稿 | passed、errors、warnings、明细 | |
| `validate_change` | CHG 号 | 同上 | 对实例里已有 CR |
| `create_change` | 草稿、confirmed | CHG 号、sys_id、链接、task 号 | 见安全闸 |
| `add_change_tasks` | CHG 号、tasks | task 号列表 | |
| `update_change` | CHG 号、字段 map | 更新后字段 | 键是 SN 列名 |
| `sync_history` | sinceDate / limit | 本次拉取数、库总数、最新时间 | 空 sinceDate 续上次 |
| `read_rules` | 无 | 硬规则列表 + 软规则文本 | |
| `add_hard_rule` | 一条规则的 yaml、理由 | 结果 | 解析校验 + 查重 + changelog |
| `add_soft_rule` | id、文本、理由 | 结果 | 追加 + changelog |
| `save_example` | CHG 号、分类 | 文件路径 | 写 examples/<分类>/ |
| `save_rejected` | CHG 号、理由 | 文件路径 | 写 rejected/ |
| `list_examples` / `read_example` | 相对路径 | 列表 / 内容 | |

## 11. ServiceNow 接口依赖

只用标准 Table API:

| 操作 | 请求 |
|---|---|
| 读 | `GET /api/now/table/{table}?sysparm_query=…&sysparm_display_value=true&sysparm_exclude_reference_link=true` |
| 建 | `POST /api/now/table/{table}?sysparm_input_display_value=true` |
| 改 | `PATCH /api/now/table/{table}/{sys_id}?sysparm_input_display_value=true` |

| 表 | 用途 | 权限 |
|---|---|---|
| `change_request` | CR 主表 | 读;上线后 create + write |
| `change_task` | CR 下的 task | 读;上线后 create |
| `sysapproval_approver` | 审批记录和打回备注 | 读 |
| `sys_journal_field` | work notes / comments | 读 |
| `cmdb_ci_server`、`cmn_schedule` | 只在 `inventory.file` 留空、改查 CMDB 时用 | 读 |

服务器和窗口信息默认来自 Excel,不查 CMDB。需要按实例改代码的只剩 `RestServiceNowClient.findRejectedApprovals`(如果你们不走标准审批表)。

## 12. 扩展点

| 想做的事 | 改哪 |
|---|---|
| 加一类变更 | `knowledge/templates/xxx.yaml`,不改代码 |
| 加一条规则 | `hard-rules.yaml` / `soft-rules.md`,或让 Copilot 用 `add_*_rule` |
| 加一种硬规则类型 | `RuleEngine.check()` 加 case,`HardRule` 注释补说明 |
| 加一个工具 | `tools/` 加 `@Tool` 方法,在 `McpToolConfig` 注册 |
| 接 PAM / 其他系统 | 新建 gateway 接口 + REST 实现,加工具 |
| 换 HTTP 传输让 server 常驻 | 依赖换 `spring-ai-starter-mcp-server-webmvc`,`mcp.json` 改 `type: http` |
| 相似检索升级向量检索 | `HistoryStore.findSimilar` 换实现,接口不变 |
| Copilot Studio 也用 | MCP server 不变,skills 流程搬到 topic |

## 13. 已知限制

- **窗口规则是全局的提醒**。HR-018 对所有 CR 按周日 00:00-06:00 给 warn;Excel 里单独填了窗口的服务器(比如 dev 机)只会在 `get_change_windows` 里显示出来,规则不会按行区分。需要的话可以给 HR-018 加 `when` 条件或按环境拆成多条。
- **Excel 服务器名必须和 ServiceNow 里的 CI 名一致**,创建 CR 时 `cmdb_ci` 按名字写入。
- **多 CI 一个 CR**。`cmdb_ci` 只放第一台,其余写在描述里。要一台一个 CR 或用 `task_ci` 关联表,需加工具。
- **相似检索是 LIKE**。几千条内够用,再大换全文索引或向量。
- **mock 的 encoded query 只支持子集**。`=`、`LIKE`、`>=`、`^`。
- **PAM 关联未做**,已明确后置。
