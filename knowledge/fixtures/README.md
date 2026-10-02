# fixtures:真实接口返回的回放样本

接通公司接口后,在 Copilot 里说 `save_fixture change CHG0012345`(或 `save_fixture ice <ICE 号>`),
程序把这张单的**原始返回**连同取它用的参数存到这里:

```
knowledge/fixtures/servicenow/get-change.CHG0012345.json
knowledge/fixtures/ice/get-ice.<id>.json
```

`mvn test` 里的 `FixtureReplayTest` 会读本机的 `cr-agent.yml`,把每个样本当作接口返回回放一遍,
验证 `result` 路径、`tasks` 路径、`field-map` 还能解析出单号和字段。以后接口或 yml 改了,测试先红。

- 没有 `cr-agent.yml` 或没有样本时测试自动跳过,不影响别的机器编译。
- 样本里的敏感值(人名、IP、密码)可以直接在文件里改掉或删掉,只要结构不变。
- 文件格式:`{ "api": "servicenow", "endpoint": "get-change", "params": {"number": "..."}, "response": {...原始返回...} }`
