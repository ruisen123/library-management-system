import sys, os, re
sys.stdout.reconfigure(encoding='utf-8')
root = r"C:\Users\23754\Desktop\软件\SpringBoot Vue library"
checks = [
  ("DOC-4 原文", "thesis/过程材料/创新点巡检规则_核验结论.md", 40),
  ("归档README", "审查归档/01_审查过程全记录/系统测试/README.md", 65),
  ("md附录合计", "thesis/毕业论文_优化版/09_附录.md", 300),
]
for label, rel, ln in checks:
    p = os.path.join(root, rel.replace("/", os.sep))
    if not os.path.exists(p):
        print(label, "MISSING", rel); continue
    lines = open(p, encoding="utf-8", errors="ignore").read().splitlines()
    t = lines[ln-1] if len(lines) >= ln else "(no line)"
    print(label, "L%d:" % ln, t.strip()[:180])
# 找仍带"当前/最新/现行"却写 361 的行
pat = re.compile(r"(361)")
hits = []
for dp, dns, fns in os.walk(root):
    dns[:] = [d for d in dns if d not in {"node_modules", ".git", "backup", "target", "dist", ".idea", ".vscode"}]
    for fn in fns:
        if not fn.endswith(".md"): continue
        p = os.path.join(dp, fn)
        try: t = open(p, encoding="utf-8", errors="ignore").read()
        except Exception: continue
        for i, line in enumerate(t.splitlines(), 1):
            if "361" in line and ("当前" in line or "最新" in line or "现行" in line):
                hits.append((os.path.relpath(p, root), i, line.strip()[:120]))
print("--- 361+当前/最新/现行 ---")
for h in hits[:20]:
    print("%s:%d: %s" % h)
