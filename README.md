# cr-agent

两件事,一个 Java 程序,在 IntelliJ 里跑:

1. **变更单(CR)**:按你们的规则和历史把 ServiceNow 变更单填好、校验过、经你确认后通过你们的接口创建。
2. **日课驾驶舱**:你贴给 Copilot 的任务它整理;早上说"早安"给你今天的三件事,晚上说"收工"把值得留的沉淀成知识。

ServiceNow 的接口**完全由你在配置里描述**,程序不预设任何表、字段或路径。

---

## 1. 怎么跑

前置:JDK 17+、Maven、IntelliJ + GitHub Copilot(Agent 模式)。

```bash
mvn -q -DskipTests package        # 产出 target/cr-agent.jar
```

**只用页面**:双击 `start.bat`,浏览器自动打开 http://127.0.0.1:7777/app/ 。关窗口就停。(`run.bat` 是给 Copilot 的,不弹浏览器。)

**页面开机自启,不依赖 IntelliJ**:`cockpit-autostart.bat install` 一次,以后登录 Windows 就有页面(后台 javaw,无窗口,日志 `logs/cockpit.log`);`remove` 取消,`start` / `stop` 手动起停。Copilot 之后拉起自己的 cr-agent 时发现端口被占只记一条 warn,工具照常用,两个进程读写的是同一批文件。

**在 Copilot 里用**:IntelliJ → Copilot Chat → Agent 模式 → 工具图标 → Configure MCP,写入(绝对路径,反斜杠写两个):

```json
{ "servers": { "cr-agent": { "type": "stdio", "command": "C:\\path\\cr-agent\\run.bat", "args": [] } } }
```

重启 IntelliJ。工具列表里出现 `get_change`、`lookup_ci` 就接上了。Copilot 会自己拉起程序,页面同时可用。

**命令行调任何一个工具**(排查用,不需要 Copilot):

```bash
java scripts/CallTool.java sn_endpoints
java scripts/CallTool.java get_change "{\"number\":\"CHG0012345\"}"
```

---

## 2. 配置:一个文件 `cr-agent.yml`

复制 `cr-agent.example.yml` 为 `cr-agent.yml`(不进 git),三段:

**ServiceNow 接口**:根地址、认证、接口清单。认证 `bearer` 读环境变量 `SN_TOKEN`,`basic` 读 `SN_PASSWORD`(PowerShell `setx` 后重启 IntelliJ)。

```yaml
servicenow:
  base-url: https://sn-gateway.company.internal
  auth: { type: bearer, token-env: SN_TOKEN }
  endpoints:
    get-change:    { method: GET,   path: /change/${number}, result: data, tasks: tasks }
    create-change: { method: POST,  path: /change,           result: data }
    update-change: { method: PATCH, path: /change/${number}, result: data }
```

只有这三个接口:读、建、改。对应三个工具 `get_change`、`create_change`、`update_change`;建和改都要你确认才发。

**ICE 接口**:CR 还要在 ICE 里登记一份,预留三个接口:读、建、改。写法和上面一样,放在 `ice:` 段(可以是另一个地址、另一套认证,密码用 `ICE_PASSWORD` 之类的环境变量):

```yaml
ice:
  base-url: https://ice.company.internal
  auth: { type: basic, user: svc_cr, password-env: ICE_PASSWORD }
  id-field: id                                   # 返回里哪个键是 ICE 记录号
  endpoints:
    get-ice:    { method: GET,  path: /ice/${id}, result: data }   # 参数:id
    create-ice: { method: POST, path: /ice,       result: data }   # 参数:number(CR 号)、fields、每个字段
    update-ice: { method: PUT,  path: /ice/${id}, result: data }   # 参数:id、fields、每个字段
```

对应工具 `get_ice`、`create_ice`、`update_ice`,建和改要确认。create-cr 建完 CR 会接着提议登 ICE,ICE 号挂在任务上和 CHG 号并排显示。不配 `ice:` 段就是关着的,`status` 会说明,工具会拒绝。要自己写 Java 就 `ice.client: company`,填 `CompanyIceClient.java` 的三个 TODO。

**React 界面**:http://127.0.0.1:7777/app/ 。日课(今天:今天做 / 盯着 / 随手记 / 收工;任务:历史、搜索、退回;知识沉淀),变更单列表 / 详情(读、编辑保存、task 建 / 取消 / 关闭、ICE 关联)/ 新建(模板起草、校验、创建),ICE 列表 / 详情(查分数、历史)/ 登记,本地台账,字段目录。http://127.0.0.1:7777/ 直接跳到它。源码在 `web/`,构建产物已提交到 `src/main/resources/static/app`,公司机器只需要 Maven。改前端:在家 `cd web && npm install && npm run dev`(代理到 7777),改完 `npm run build` 再 `mvn package` 提交。REST 在 `/api/v1`,返回体 `{code, message, data}`。

**字段不写死**:表单按 `knowledge/forms/{change,task,ice}.json` 渲染(key、标签、类型、分组、必填、只读、选项),接口多返回的键在详情页"收进目录";页面「字段目录」可直接改。代码里只认几个标准键,其余字段原样透传。

页面上点保存、创建就是你的确认,和 Copilot 里说"创建"一样走同一套闸(硬规则 error 不清零不建),每次点击都进审计日志。留存文件在 `cockpit/records/{change,ice,task}/<单号>.json`:字段、task、硬规则结果、接口原始返回、读取时间。

接口怎么实现,二选一(`servicenow.client`):

| `client:` | 怎么做 | 适合 |
|---|---|---|
| `yaml`(默认) | 上面那样在 yml 里描述,不写 Java | 接口是普通 HTTP + JSON |
| `company` | 打开 `src/.../servicenow/CompanyServiceNowClient.java`,填三个 TODO 方法 | 接口有特殊逻辑,或你想自己掌控 |

`company` 模式下认证、HTTP、JSON、报错都已经在 `SnHttp` 里做好,每个方法只需三行:调哪个路径、记录在返回的哪里、组装成 `ChangeRecord`。文件里有示例代码。三个方法的契约在 `ServiceNowClient.java`。

yaml 模式的规则:

| 键 | 意思 |
|---|---|
| `${xxx}` | 占位符,来自调用参数。path / query 里按文本替换;body 里按 JSON 替换(字符串带引号,对象整体插入) |
| `result` | 返回 JSON 里记录的位置,点分隔;空 = 整个返回 |
| `tasks` | 记录里 task 列表的位置,给页面和校验用,可不填 |
| `body` | 不写 = 字段平铺成 JSON;有外层包装就写模板,如 `'{"request": ${fields}, "tasks": ${tasks}}'`。update 时 `${fields}` 是要改的字段,`${number}` 是单号 |
| `headers` / `query` | 都可以带 `${xxx}` |

**服务器清单**:把你们的 Excel 放到 `knowledge/inventory.xlsx`(三列必有:服务、环境、服务器),表头在 `inventory.columns` 里对一下。`knowledge/inventory.sample.xlsx` 是格式示例,只给单元测试用。

**驾驶舱**:端口、目录,一般不用改。

---

## 3. 字段名和规则

草稿里的字段名来自模板 `knowledge/templates/*.yaml`,用的是标准名(short_description、backout_plan……),硬规则 `knowledge/rules/hard-rules.yaml` 里的 `field` 和模板一致。接口字段名不同时**不改模板和规则**,在 `cr-agent.yml` 的 `field-map` 里写对照,程序只在接口边界改一次名。

规则和范例都是文件:老板提一条新要求,改一行文件就生效,不改代码不重启。

---

## 4. 日常怎么说

| 你说 | 发生什么 |
|---|---|
| `看一下 CHG0012345` | `get_change` 读出来并跑规则,页面「变更单」视图能看全文和接口原始返回 |
| `用 create-cr skill,给 Order Portal 的 prod 打十月补丁,周日 1 点` | 查清单 → 看范例 → 套模板 → 填描述 → 校验 → 给你确认 → 调 create-change |
| `审一下 CHG0012345` | 逐条规则给出问题和改法;你说"改"它才调 update-change |
| `给 CHG… 加个 task 做回归验证` / `取消第 3 个 task` / `关掉 pre-check` | change-ops 技能:三个 task 接口各自独立调用,每次确认一次 |
| `这张单的 ICE 分数` / `ICE 窗口改成周日 2 点` | `ice_score` / `update_ice` 后再查分数,分数历史留在本地 |
| `被打回了,理由是 …` | 存档、提炼规则问你要不要加 |
| `早` | 一个字就够:Copilot 自动拉起 cr-agent,把驾驶舱页面弹到浏览器,读昨天和在等的,给你今天三件事 |
| `早安` / `记成任务` / `记一笔` / `收工` | 驾驶舱的早晚流程,见 `.github/skills/` |
| `T-0003 等 CAB 审批` / `批了` | 任务进入「等待中」,早安时单独列出不再催;说"批了"回到待办 |
| `T-0003 周日凌晨 1 点执行` | 记下执行时间;执行前两天早安会提醒检查变更单和审批,过了没标完成会问你跑了没 |
| `给 CCS PROD 打补丁的 CR 建一下` | create-cr 会认出对应任务,建好的 CHG 号自动挂到任务上 |

**聊天里的关键信息会自动留下**:你不用说"记一笔"。Copilot 每次回复结束时,把对话里以后还成立的东西(老板的要求、某台机器某个服务的事实、坑、决定、你的习惯)用 `remember` 存进当天的随手记,标"Copilot 自动记的"并附原话;已经知道的会跳过。晚上"收工"时逐条让你确认:规则进 `knowledge/rules/`,事实和习惯进 `knowledge/`,不要的就留在当天日志里不再提。工具面板里把 `remember` 设成 Always allow,它只写本地文件。

任务只记文本里有的事实:工时没说就空着,Copilot 猜的会标「AI 估」;团队邮件里别人的活会先问你是不是你的;贴进来的任务如果以前做过,它会把上次的耗时、CHG 号和坑一并说出来。`cockpit/` 是你的个人数据,已在 `.gitignore` 里。

---

## 5. 让它可控:评测、审计、授权分级

**规则回归(评测集)**:`knowledge/examples/` 和 `knowledge/rejected/` 里的 YAML 既是 Copilot 的写作范例,也是规则的测试集。过审的单不能被任何硬规则误伤,打回的单必须被抓到。`mvn test` 自动跑;Copilot 加规则时自动跑并把结果告诉你;随时可以说"跑一下 eval_rules"。过审的好单说"把 CHG… 存成范例",打回的说"CHG… 被打回了,理由是…",它就归档进去了。

**审计日志**:`logs/audit.jsonl`,每次工具调用和页面操作一行:时间、来源(copilot / page)、动作、参数、成败、耗时。查"上周二它对 ServiceNow 做了什么"不用翻聊天记录。

**工具按风险分级**:Copilot 默认每个工具调用都弹确认。在工具面板里把只读工具设成 Always allow,写操作保留确认:

| 只读,可以自动批准 | 会写东西,保留确认 |
|---|---|
| `status` `check_config` `probe`(send=false)`draft_ice` `ice_score` `remember`(只写当天随手记,晚上还要确认)`get_change` `lookup_ci` `lookup_service` `list_templates` `get_template` `build_draft` `validate_draft` `validate_change` `read_rules` `list_examples` `read_example` `eval_rules` `get_ice` `get_day` `list_tasks` `task_notes` `task_history` `search_knowledge` `read_knowledge` `cockpit_url` `open_cockpit` | `create_change` `update_change` `create_task` `cancel_task` `close_task` `create_ice` `update_ice`(代码里还要 confirmed=true)· `probe`(send=true)`save_fixture` · `add_hard_rule` `add_soft_rule` `save_example` `save_rejected` · `add_tasks` `update_task` `plan_day` `capture_note` `close_day` `save_knowledge` |

**工具按需加载(省 token)**:Agent 模式每一轮都把全部工具定义发给模型,40 个工具约五六千 token。`cr-agent.yml` 里加

```yaml
tools:
  mode: groups        # 默认 all
  # core: [lookup_service]   # 想常驻的额外工具
```

启动只注册 8 个核心工具(`status`、`remember`、`search_knowledge`、`get_change`、`get_day`、`open_cockpit`、`use_tools`、`drop_tools`),Copilot 需要时自己调 `use_tools` 加载一组:`cr`、`learn`、`cockpit`、`integration`,用完 `drop_tools` 卸掉。依赖 Copilot 插件处理 MCP 的"工具清单变了"通知;试一轮不行就把 `mode` 改回 `all`。`status` 的 `tools` 一项显示当前模式和哪些组开着。

**健康检查**:在 Copilot 里说 `status`,或命令行 `java scripts/CallTool.java status`,一次看到接口、清单、规则、范例、回归结果、页面地址、审计日志位置。

## 6. 到公司后的顺序(接口参数和返回还没定,就按这个来)

接口的形状全在 `cr-agent.yml`,程序里不写死;三样东西让它接得快:

| 东西 | 干什么 | 怎么用 |
|---|---|---|
| `check_config` | 不发请求,检查 yml:端点名齐不齐、`${占位符}` 写没写错、`result` 填没填、环境变量设没设、`field-map` / `from-change` 对不对 | Copilot 里说 `检查配置`,或 `status` 里看 `configCheck` |
| `probe` | 试一个端点。不发时只渲染:方法、完整 URL、header、body、认证从哪来;发了返回**完整原始响应**,并告诉你 `result` 路径取到了什么、`tasks` 取到几个 | `probe servicenow get-change {"number":"CHG…"}`,先 `send=false` 看请求,对了再 `send=true` |
| `field-map` | 接口字段名和标准名不一样时,只在 yml 里写对照,模板、规则、范例、页面一行不改 | `servicenow.field-map` / `ice.field-map`,见示例 |
| `ice.from-change` | ICE 字段怎么从 CR 来,写成模板;`draft_ice CHG…` 每次算出一样的结果给你确认 | 见示例 |
| `save_fixture` | 把一张真单的原始返回存进 `knowledge/fixtures/`,`mvn test` 回放它验证 yml 还能解析 | 读过一张单后说 `save_fixture change CHG…` |

顺序:

1. `cr-agent.yml` 填 `base-url`、认证,先只写 `get-change`。`检查配置` 清零 problems。
2. `probe servicenow get-change {"number":"真单号"}` 先不发看请求对不对,再发。对着返回填 `result`、`tasks`,字段名不一样就填 `field-map`。再 probe 一次直到 `resultFound=true`、`resultFields` 里有 `short_description`/`description`/`start_date`/`end_date`(或映射后的名字)。
3. `看一下 CHG…`,页面「变更单」里看字段齐不齐;`save_fixture change CHG…` 存样本,`mvn test` 过。
4. 同样方式过 `create-change`、`update-change`(账号先只给读权限,跑 create-cr 看草稿,满意再给写权限),再过 ICE 三个接口和 `from-change`。
5. 过审的好单用 `save_example` 存进 `knowledge/examples/`,以后的草稿照着写。

硬规则里的 `field`、模板里的键名、页面标签始终用标准名(`short_description`、`description`、`start_date`、`end_date`、`cmdb_ci`、`assignment_group`…),接口那边叫什么由 `field-map` 负责。

出错看 `logs/cr-agent.log`。详细设计见 [DESIGN.md](DESIGN.md)。
