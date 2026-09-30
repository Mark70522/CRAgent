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

**密码不放进工作区。** `cr-agent.yml` 在项目目录里,Copilot 能读到工作区内任何文件,`.gitignore` 拦不住它。密码放工作区外面,两种都行:

| 方式 | 怎么做 |
|---|---|
| 环境变量 | PowerShell 执行一次 `setx SERVICENOW_PASSWORD "你的密码"`(或 `setx SERVICENOW_TOKEN "..."`) |
| 工作区外的配置文件 | 建 `C:\Users\<你>\.cr-agent\cr-agent.yml`,内容和工作区的 `cr-agent.yml` 同格式,只写 `servicenow:` 下的 `password:` 或 `token:`。想放别处就设环境变量 `CR_AGENT_CONFIG` 指向那个文件 |

两种都设了之后**重启 VS Code**(环境变量是进程启动时继承的)。

优先级从低到高:工作区 `cr-agent.yml` → 工作区外的文件 → 环境变量。同一项后者覆盖前者,所以可以把不敏感的放工作区、敏感的放外面。

Java 程序本身不会把配置回传给 Copilot,密码只用于对 ServiceNow 的 HTTPS 认证,不写日志。

接公司实例时,把 `cr-agent.example.yml` 的内容复制过来填(密码除外):

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
  # password / token 不写这里, 放环境变量或工作区外的配置文件 (见上表)
  # proxy-host: proxy.company.com       # 走代理时打开
  # proxy-port: 8080
```

维护窗口默认周日 00:00-06:00(规则 HR-018,`knowledge/rules/hard-rules.yaml`)。这条是提醒不是拦截:你要的时间在窗口外,Copilot 会说一句让你确认,确认了就按你的时间建。以后默认窗口变了改那一条就行。

服务账号权限:先只给读(`change_request`、`change_task`、`cmdb_ci_server`、`sysapproval_approver`、`sys_journal_field`),草稿质量满意了再给 `change_request` 和 `change_task` 的创建和写。

---

## 2.3 公司接口和标准的不一样怎么办

Java 里不写死任何表名、列名、路径,分两种情况:

**情况 A:接口形状一样,只是名字不一样**(最常见:加了 `u_` 字段、审批走自定义表、外面包了网关)。全部在 `cr-agent.yml` 的 `servicenow.api` 下改,不碰代码:

| 不一样的地方 | 改哪 |
|---|---|
| 路径不是 `/api/now/table` | `api.base-path` |
| 网关要额外的 header / 参数 | `api.headers` / `api.default-params` |
| 返回 JSON 的结构不一样 | `api.result-path` / `api.record-path`(多层用点分隔) |
| 表名不一样 | `api.tables.*` |
| CR / task 的列名不一样 | `api.change-fields` / `api.task-fields`,左边逻辑名右边真实名,只写不一样的。程序读写时双向翻译,规则和模板永远用逻辑名 |
| 审批记录在别的表、列名不同 | `api.approval.*`,查询里 `{since}` `{sys_id}` 会被替换 |
| 工作备注不在 `sys_journal_field` | `api.journal.*` |

到公司后的做法:先在 Copilot 里说"用 sn_raw_get 查 change_request 表 number=CHG0012345",看真实返回的列名;再说"用 sn_raw_get 查 sysapproval_approver 表 state=rejected 取 3 条",看审批长什么样;对着填 yml;用 `sn_config` 确认配置生效。整个过程只读。

不想开 Copilot 也能查,命令行直接调任何一个工具:

```bash
java -cp "libs/*" scripts/CallTool.java sn_config
java -cp "libs/*" scripts/CallTool.java sn_raw_get "{\"table\":\"change_request\",\"query\":\"number=CHG0012345\"}"
java -cp "libs/*" scripts/CallTool.java get_change "{\"number\":\"CHG0012345\"}"
```

离线编译的加环境变量 `CR_AGENT_LAUNCH=offline`。

**情况 B:接口形状完全不同**(不是 REST Table API,或者要走公司自己的变更服务)。写一个新类实现 `ServiceNowGateway` 接口(9 个方法,见 `servicenow/ServiceNowGateway.java`),加上:

```java
@Component
@ConditionalOnExpression("'${servicenow.mock:true}' == 'false' && '${servicenow.adapter:}' == 'acme-gateway'")
public class AcmeGatewayClient implements ServiceNowGateway { ... }
```

然后 `cr-agent.yml` 里 `servicenow.adapter: acme-gateway`。工具、规则、模板、Excel、历史库全部不用动,它们只认接口。

## 2.4 在 IntelliJ 里用

GitHub Copilot 的 JetBrains 插件同样有 Agent 模式和 MCP,server 不用改。两处不同:

1. **MCP 配置**:Copilot Chat 切到 Agent 模式,点工具图标 → Configure MCP(或 Settings → GitHub Copilot → MCP),
   打开的是全局 `mcp.json`(Windows 一般在 `%LOCALAPPDATA%\github-copilot\intellij\mcp.json`)。不支持 `${workspaceFolder}`,写绝对路径:

```json
{
  "servers": {
    "cr-agent": {
      "type": "stdio",
      "command": "C:\\path\\to\\cr-agent\\run.bat",
      "args": []
    }
  }
}
```

   `run.bat` 会先切到项目目录再启动,所以 `cr-agent.yml`、`knowledge/` 都能找到。离线编译的换成 `run-offline.bat`。

2. **Skills**:`.github/skills/*/SKILL.md` 在 JetBrains 插件里是否自动识别,取决于插件版本。识别不了的话有两个办法:
   在对话里用 `#file` 把对应的 `SKILL.md` 附上再提要求;或者把 `create-cr/SKILL.md` 的内容并进
   `.github/copilot-instructions.md`(JetBrains 支持这个文件)。工具调用不受影响,只是流程说明要让它看得到。

## 2.5 公司里下不了包怎么办

运行不需要下包:`target/cr-agent.jar` 是 fat jar,拷过去只要有 JDK 17 就能跑。

在公司要改代码、又没有 Maven 私服时,用离线编译:

1. 在能上网的机器上 `mvn dependency:copy-dependencies -DoutputDirectory=libs`(本仓库已经导好,`libs/` 82 个 jar,58MB),把整个项目目录连 `libs/` 一起拷到公司机器。
2. 公司机器上双击 `build-offline.bat`:只用 JDK 自带的 `javac` 和 `jar`,不需要 Maven、不需要网络,产物在 `out/`。
3. `.vscode/mcp.json` 的 `command` 改成 `run-offline.bat`,`args` 留空即可:

```json
"command": "${workspaceFolder}/run-offline.bat",
"args": []
```

有 Maven 私服的话不用这些,`~/.m2/settings.xml` 配个 mirror,照常 `mvn package`。

## 3. 启动:三步

```bash
mvn -q -DskipTests package                    # 1. 构建,产出 target/cr-agent.jar
java -cp "libs/*" scripts/SmokeTest.java      # 2. 自检,应打印 ALL OK(可跳过;只要 JDK,不要 Python)
```

离线编译的用 `java -cp "libs/*" scripts/SmokeTest.java --offline`。`scripts/*.py` 是同样的测试的 Python 版,没有 Python 环境就不用管。

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

前置:JDK 17+、VS Code + GitHub Copilot(支持 Agent 模式)。Maven 只在有网的机器上构建时需要,公司里可以用离线编译(见 2.5)。不需要 Python。

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
| 连 ServiceNow 返回 401 | 密码没被读到:环境变量没设、外部配置文件路径不对、或 VS Code 没重启。新开 PowerShell 用 `echo $env:SERVICENOW_PASSWORD` 检查;`logs/cr-agent.log` 开头不会打印密码,但会列出加载了哪些配置文件 |
| 中文乱码 | 跑 `python scripts/encoding_check.py`,应显示 utf-8 True |
| 启动报 "has no column 'xxx'" | Excel 表头和 `cr-agent.yml` 的 `inventory.columns` 对不上,错误里列出了实际找到的表头 |
| `lookup_ci` / `lookup_service` 返回空 | 名字拼错或 Excel 里没有;`lookup_service` 不传参数会列出所有服务名 |
| `create_change` 说 hard-rule error | 草稿没过规则,这是故意的,先 `validate_draft` 看哪条 |
| `create_change` 说要 confirmation | Copilot 应先给你看草稿,你说"创建"它才传 `confirmed=true` |
| 写入返回 403 | 账号没有 create 权限 |
| 时间差几小时 | 把服务账号的时区设成本地时区 |

---

## 6. 日课驾驶舱:任务、早晚仪式、知识沉淀

同一个 server 里的第二个功能,和 CR 那套共用 Copilot 和配置。任务不接 Jira,你贴给 Copilot 它整理。

**一天怎么用**

| 你说 | 发生什么 |
|---|---|
| `早安` | 读昨天没做完的、所有未完成任务、相关知识,给你今天的三件事(每件带"为什么",出处是你自己的记录),排时间轴,确认后写进今天的计划 |
| 贴一段邮件/会议记录,说 `记成任务` | 拆成任务存进 backlog,和已有的自动合并,回你一个清单 |
| `记一笔:坑 opatch 之后要重启监听` | 落到今天的随手记,开头的「决定 / 坑 / 学到」自动分类 |
| `上次 srv-db-01 回退花了多久` | 只从你的知识文件和任务笔记里找,答案带文件名 |
| `收工` | 统计完成和投入,把带分类的随手记提出来让你逐条确认是否入库,给明天的草稿,关闭今天 |
| `九月我做了什么` / `补丁相关的任务都翻出来` | `task_history`:按时间排的历史,每条带来源原文、过程记录、沉淀出的知识、预估和实际耗时、拖了几天;关键字能搜到标题、原文、笔记和知识 |

对应三个 skill:`morning-brief`、`capture`、`evening-close`。页面里的「任务历史」是同一个查询:按创建 / 完成 / 更新时间排,按状态筛,关键字实时搜,点开一条看全部信息。

**页面**:server 启动后打开 http://127.0.0.1:7777/ (端口在 `cr-agent.yml` 的 `cockpit.port`)。左边最下面的「变更单」视图输入 CHG 号能直接从 ServiceNow 读一张 CR:字段、六段计划文本、task 时间表、审批记录、工作备注,加上硬规则校验结果和同一台机器的过审 CR。页面和 Copilot 读写同一批文件,勾任务、记一笔、点「沉淀」、收尾都可以在页面上做;简报和"值得留下的"文字由 Copilot 写,页面只展示。

**数据全是文件**,在 `cockpit/`:

```
cockpit/
├── backlog.json            所有任务
├── days/2026-09-30.json    当天:简报、计划、时间轴、随手记、总结
├── task-notes/T-0001.md    每个任务自己的笔记(贴进来的原文、后续)
├── knowledge/<主题>.md      你确认留下的,每条带日期和来源任务
└── stats.json              每个收尾过的日子一行
```

想改就直接改文件;想备份就把目录进 git。

## 7. 更多

- 详细设计(架构、每个流程、规则格式、工具清单、扩展点):[DESIGN.md](DESIGN.md)
- 规则文件格式和模板格式:直接看 `knowledge/rules/hard-rules.yaml` 和 `knowledge/templates/os-patch.yaml` 里的注释
- 开发:`mvn test` 跑单元测试;新增工具在 `src/main/java/.../tools/` 加 `@Tool` 方法并在 `McpToolConfig` 注册
