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

**只用页面**:双击 `run.bat`,浏览器开 http://127.0.0.1:7777/ 。关窗口就停。

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

草稿里的字段名来自模板 `knowledge/templates/*.yaml`,**写成你们接口要的名字**。自带的三个模板用的是标准名(short_description、backout_plan……),接口字段不同就改模板里的键名,程序不做翻译。硬规则 `knowledge/rules/hard-rules.yaml` 里的 `field` 要和模板一致。

规则和范例都是文件:老板提一条新要求,改一行文件就生效,不改代码不重启。

---

## 4. 日常怎么说

| 你说 | 发生什么 |
|---|---|
| `看一下 CHG0012345` | `get_change` 读出来并跑规则,页面「变更单」视图能看全文和接口原始返回 |
| `用 create-cr skill,给 Order Portal 的 prod 打十月补丁,周日 1 点` | 查清单 → 看范例 → 套模板 → 填描述 → 校验 → 给你确认 → 调 create-change |
| `审一下 CHG0012345` | 逐条规则给出问题和改法;你说"改"它才调 update-change |
| `被打回了,理由是 …` | 存档、提炼规则问你要不要加 |
| `早安` / `记成任务` / `记一笔` / `收工` | 驾驶舱的早晚流程,见 `.github/skills/` |

---

## 5. 到公司后的顺序

1. `cr-agent.yml` 填 `base-url`、认证、`get-change` 一个接口。`java scripts/CallTool.java get_change "{\"number\":\"真实单号\"}"`,返回和页面上一致就通了。
2. 页面「变更单」里看同一张单,底部"接口原始返回"告诉你字段叫什么;对着改模板的键名和规则的 `field`。
3. 配 `create-change` 和 `update-change`,账号先只给读权限,跑 create-cr 看草稿;满意了再给写权限。
4. 过审的好单用 `save_example` 存进 `knowledge/examples/`,以后的草稿照着写。

出错看 `logs/cr-agent.log`。详细设计见 [DESIGN.md](DESIGN.md)。
