# test/artifacts 说明

截至 2026-11-04 现存 12 份 `audit_*.md` 与 7 份 `mail_*.md`（其中 10 份 `audit_*.md` 为 2026-11-02 清理后保留、2 份为 11-04 新增；更早的 102 份报告已于 2026-11-02 清理）。

## 保留策略

- 保留最近 **10 份** `audit_*.md` 与全部 `mail_*.md`；更早的 `audit_*.md` 可清理。
- **先预览**（`-WhatIf` 只列出将删除的文件，不实际删除）：
  ```powershell
  Get-ChildItem test/artifacts -Filter "audit_*.md" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -Skip 10 |
    Remove-Item -WhatIf
  ```
- 确认预览列表无误后，再去掉 `-WhatIf` 执行。**本机删除不进回收站、不可恢复，务必先看预览。**
- 2026-11-02 已按此口径清理过一次（`audit_*.md` 112 → 10 份）。
- 当前 12 份中，11-04 新增的 2 份是最新一轮基线证据，**尚未按策略清理至 10 份**——下次清理时按上述命令执行即可，不影响现状。

`test/系统测试报告.md`、`docs/08`、`AGENTS.md`、`审查归档/02` 中引用的旧报告名（共 **36 个、121 处**）属历史引用，文件已不在，不再可打开；按“历史不倒改”保留原文。
