# test/perf —— 可选性能实测脚本

`bench_audit_rules.py`：巡检 14 项检查（10 条规则）在万级数据（1 万图书 / 5 万借阅）下的逐条 SQL 耗时，
用于复现论文 6.2.2 的「最慢 134 ms、串行合计约 1.06 s」口径（2026-10-20 两次复测取小，见 `test/artifacts/bench_audit_rules_20261020.md`）；最终代码（新 jar）2026-10-24 复测 R1 98 ms、合计 925 ms，见 `test/artifacts/bench_audit_rules_20261024.md`。

`verify_audit_rules.py`：巡检 14 项检查（10 条规则）的双向验证（注入 14 类异常应全部检出、4 类合法边界应不误报），对应论文 6.2.2 的「14/14 检出、4/4 未误报」。

## 用法

```powershell
$env:LIBMS_DB_PWD = "***"      # 必填：测试库口令（脚本不提供默认值）
python test/perf/bench_audit_rules.py --dry-run   # 只看计划，不建库
python test/perf/bench_audit_rules.py             # 实跑（约 1-3 分钟）
```

- `LIBMS_MYSQL` 可选，缺省从 PATH 查找 mysql；找不到会打印原因并跳过（退出码 0）。
- 脚本会创建并 DROP 探针库 `library_all14`，只对测试库执行；结束后删除 `test/perf/_tmp`。
- 口径：规则 SQL 逐条耗时之和，不是 `/audit/run` 端到端时延。
