# cr-agent

让 GitHub Copilot 按我们的规则创建 ServiceNow Change Request(CR),目标是 **CR 一次过审**。

---

## 1. 它是怎么工作的(先看这一节)

一句话:**Copilot 负责听懂你的话和写文字,一个本地 Java 程序负责查 ServiceNow、算时间、按规则检查,规则和范例存在几个文本文件里。**

用一个真实场景走一遍。你在 VS Code 的 Copilot Chat 里说:

> 给 srv-app-01 和 srv-app-02 打 10 月的 Windows 补丁,10 月 11 号(周日)凌晨 1 点开始

也可以只说服务名:"给 Order Portal 的 prod 打补丁",Copilot 会用 `lookup_service` 从 Excel 里把这个服务 prod 环境的所有服务器列出来让你确认。

后面发生的事:

| 步骤 | 谁做 | 做什么 | 结果 |
|---|---|---|---|
| 1 | Copilot | 读 `.github/skills/create-cr/SKILL.md`,知道创建 CR 该按什么顺序做 | 知道流程 |
| 2 | Java 程序 | `lookup_ci`:在你们的 Excel 服务器清单(`knowledge/inventory.xlsx`)里查这两台机器 | 确认是 prod、属于 Order Portal、负责组 Wintel Ops、维护窗口周日 00:00-06:00 |
| 3 | Java 程序 | `find_similar_changes`:在本地历史库里找这两台机器以前打补丁、过审的 CR | 找到 3 个,给 Copilot 当范例 |
| 4 | Java 程序 | `build_draft`:套 `knowledge/templates/os-patch.yaml` 模板 | 基本字段填好(type、risk、group),4 个 task 按时长排好时间(01:00 快照 → 01:30 打补丁 → 03:30 重启验证 → 04:15 收尾),描述留出 6 个章节的空位 |
| 5 | Copilot | 参考范例,把描述 6 个章节写满:变更对象、补丁清单、影响范围、步骤、验证、回退 | 描述完整 |
| 6 | Java 程序 | `validate_draft`:按 `knowledge/rules/hard-rules.yaml` 逐条检查 | 比如"标题必须 [PATCH] 开头""prod 必须有 backout plan"。不过的 Copilot 改,改到过为止。时间不在周日 00:00-06:00 只是提醒,你确认了就照你的时间 |
| 7 | Copilot | 把草稿和每个字段的来源列给你看 | 你确认或改 |
| 8 | Java 程序 | `create_change`:写进 ServiceNow,状态 New | 返回 CHG 号和链接,你去提交审批 |

三个关键点:

- **规则不在代码里,在 `knowledge/rules/` 的文件里。** 老板说"以后 CR 必须写回退耗时",你往文件里加一行,下一个 CR 就遵守。不用改代码不用重启。
- **"学习"= 攒规则和范例。** CR 被打回,你在 Copilot 里说"CHG… 被打回了,理由是 …",它把这个 CR 和理由存档,提炼出规则问你要不要加,加了以后再也不犯。过审的好 CR 存成范例,下次写描述照着写。
- **写操作有闸。** 没给你看过草稿不会创建;硬规则没过不会创建;只创建到 New,提交审批永远是人。

各部分在哪:

```
cr-agent/
├── cr-agent.yml            ← 唯一要改的配置(实例地址、账号、Excel 列名)
├── knowledge/              ← 规则、模板、范例,老板的要求都在这
│   ├── inventory.xlsx           你们的服务器清单(service / 环境 / server)
│   ├── rules/hard-rules.yaml    机器检查的规则
│   ├── rules/soft-rules.md      Copilot 判断的规则(描述够不够详细之类)
│   ├── templates/*.yaml         每类变更的默认字段 + task 序列
│   ├── examples/                过审的好 CR
│   └── rejected/                被打回的 CR + 理由
├── .github/skills/         ← 告诉 Copilot 每种事该按什么步骤做
├── src/                    ← Java 程序(查 ServiceNow、算时间、检查规则)
└── target/cr-agent.jar     ← 构建产物,Copilot 启动它
```

---

## 2. 配置:一个 Excel + 一个 yml

**服务器清单:`knowledge/inventory.xlsx`**

把你们现有的 Excel 放到这里(或者在 `cr-agent.yml` 里指向它原来的路径)。程序只需要三列:服务、环境、服务器名。IP、OS、负责组、维护窗口这几列有就用,没有就算了。表头叫什么都行,在 yml 里对一下就好。文件改了不用重启,下次调用自动重新读。

自带的示例文件有 Order Portal 和 Billing 两个服务、9 台机器,可以直接拿来试。

**其他配置:`cr-agent.yml`**(项目根目录,不进 git)

本机测试什么都不用填,已经是 mock 模式:

```yaml
servicenow:
  mock: true
```

接公司实例时,把 `cr-agent.example.yml` 的内容复制过来填:

```yaml
inventory:
  file: ./knowledge/inventory.xlsx      # 你们的 Excel
  columns:                              # 表头叫什么就写什么
    service: Service
    environment: Environment
    server: Server
    owner-group: Owner Group

servicenow:
  mock: false
  instance: https://company.service-now.com
  user: svc_cr_agent
  password: "..."                       # 或者填 token
  # proxy-host: proxy.company.com       # 走代理时打开
  # proxy-port: 8080
```

维护窗口默认周日 00:00-06:00(规则 HR-018,`knowledge/rules/hard-rules.yaml`)。这条是提醒不是拦截:你要的时间在窗口外,Copilot 会说一句让你确认,确认了就按你的时间建。以后默认窗口变了改那一条就行。

服务账号权限:先只给读(`change_request`、`change_task`、`cmdb_ci_server`、`sysapproval_approver`、`sys_journal_field`),草稿质量满意了再给 `change_request` 和 `change_task` 的创建和写。

---

## 3. 启动:三步

```bash
mvn -q -DskipTests package       # 1. 构建,产出 target/cr-agent.jar
python scripts/smoke_test.py     # 2. 自检,应打印 ALL OK(可跳过)
```

3. 用 VS Code 打开 `cr-agent` 目录,Copilot Chat 切到 **Agent** 模式。`.vscode/mcp.json` 已经配好,工具列表里应出现 `lookup_ci`、`build_draft` 等。没出现就打开 `.vscode/mcp.json`,点上方的 Start。

然后试:

```
用 create-cr skill,给 Order Portal 的 prod 打 2026-10 的 Windows 月度补丁,10 月 11 日凌晨 1 点开始
```
```
用 review-cr skill 审一下 CHG0030004
```
```
用 learn-rules skill,从今年被打回的 CR 里提炼规则
```

示例 Excel 里有 2 个服务 9 台服务器,mock 的 ServiceNow 里有 3 个过审 CR、1 个被打回的 CR(CHG0030004)。

前置:JDK 17+、Maven 3.9+、VS Code + GitHub Copilot(支持 Agent 模式)。Python 只给 smoke test 用。

---

## 4. 接公司实例后怎么推进

| 阶段 | 做什么 | 完成标志 |
|---|---|---|
| 1 | 换上你们的 Excel,`cr-agent.yml` 填实例和只读账号;Copilot 里 `lookup_service` 查一个服务、`get_change` 拉一个真 CR | 服务器列表对,CR 字段和页面上一致 |
| 2 | "用 sync-history skill 同步 2025 年以来的历史" | `find_similar_changes` 搜得到 |
| 3 | "用 learn-rules skill 提炼规则",确认的写进规则文件;好 CR 用 `save_example` 存范例 | `knowledge/rules/` 有内容 |
| 4 | 用真实需求跑 create-cr,只看草稿不创建,调模板和规则 | 草稿不用改就能提交 |
| 5 | 账号加写权限,正式用 | |
| 6 | 每次被打回,在 Copilot 里说一句,规则自动累积 | 打回率下降 |

---

## 5. 常见问题

| 现象 | 处理 |
|---|---|
| Copilot 里没有 cr-agent 工具 | 先 `mvn package`;再在 `.vscode/mcp.json` 点 Start;看 `logs/cr-agent.log` |
| 启动即退出 | 看 `logs/cr-agent.log` 最后的异常,常见是 `mock: false` 但没填 `instance` |
| 中文乱码 | 跑 `python scripts/encoding_check.py`,应显示 utf-8 True |
| 启动报 "has no column 'xxx'" | Excel 表头和 `cr-agent.yml` 的 `inventory.columns` 对不上,错误里列出了实际找到的表头 |
| `lookup_ci` / `lookup_service` 返回空 | 名字拼错或 Excel 里没有;`lookup_service` 不传参数会列出所有服务名 |
| `create_change` 说 hard-rule error | 草稿没过规则,这是故意的,先 `validate_draft` 看哪条 |
| `create_change` 说要 confirmation | Copilot 应先给你看草稿,你说"创建"它才传 `confirmed=true` |
| 写入返回 403 | 账号没有 create 权限 |
| 时间差几小时 | 把服务账号的时区设成本地时区 |

---

## 6. 更多

- 详细设计(架构、每个流程、规则格式、工具清单、扩展点):[DESIGN.md](DESIGN.md)
- 规则文件格式和模板格式:直接看 `knowledge/rules/hard-rules.yaml` 和 `knowledge/templates/os-patch.yaml` 里的注释
- 开发:`mvn test` 跑单元测试;新增工具在 `src/main/java/.../tools/` 加 `@Tool` 方法并在 `McpToolConfig` 注册
