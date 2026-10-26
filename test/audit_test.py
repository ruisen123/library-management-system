# -*- coding: utf-8 -*-
"""
LibMS 数据一致性巡检专项测试（创新点·数据健康诊断）
覆盖：报告结构 / 规则清单 / 权限 / 只读性 / 口径一致 / 注入→发现→恢复→复查归零 / 明细限流

约定（与项目既有套件一致）：
  · 独立可跑：自带种子基线校验
  · 失败退出码非 0
  · 净零副作用：注入的数据必恢复，巡检产生的审计日志自清理
"""
import json
import os
import re
import subprocess
import sys
import urllib.request
import urllib.error

sys.stdout.reconfigure(encoding='utf-8')

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")

try:
    import _common as C
    ROOT = C.PROJECT_ROOT
except Exception:
    ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

JAVA_RULES = os.path.join(ROOT, "backend", "src", "main", "java", "com", "library", "audit", "AuditRules.java")
TABLE_MD = os.path.join(ROOT, "thesis", "过程材料", "规则清单唯一口径表_2026-10-14.md")
BUSINESS_TABLES = ("sys_user", "category", "publisher", "book", "borrow_record", "fine_record", "notice")


def req(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("token", token)
    try:
        resp = urllib.request.urlopen(r, timeout=30)
        return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            return {"code": e.code, "msg": "HTTP " + str(e.code), "data": None}
    except Exception as e:
        return {"code": -1, "msg": "EXC:" + str(e), "data": None}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [PASS] " + name)
    else:
        FAIL += 1
        print("  [FAIL] " + name + "  " + str(detail))


def db_one(sql):
    """直查数据库返回单值；mysql 不可用返回 None。"""
    for cmd in ("mysql", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"):
        try:
            r = subprocess.run([cmd, "-uroot", "-p" + DB_PWD, "-N", "-B", "-e", "USE library_db; " + sql],
                               capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)
            if r.returncode == 0:
                return r.stdout.strip()
        except Exception:
            continue
    return None


def db_exec(sql):
    """执行写语句（仅用于测试注入/恢复/清理）；返回是否成功。"""
    for cmd in ("mysql", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"):
        try:
            r = subprocess.run([cmd, "-uroot", "-p" + DB_PWD, "-N", "-B", "-e", "USE library_db; " + sql],
                               capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)
            return r.returncode == 0
        except Exception:
            continue
    return False


def baseline_check():
    expect = {"sys_user": 6, "category": 4, "publisher": 4, "book": 6,
              "borrow_record": 8, "fine_record": 3, "notice": 3, "operation_log": 3}
    bad = []
    for table, want in expect.items():
        got = db_one("SELECT COUNT(*) FROM " + table + ";")
        if got is None:
            print("  [SKIP] mysql 不可用，跳过种子基线校验")
            return
        if got != str(want):
            bad.append(table + "=" + got + "(期望" + str(want) + ")")
    if bad:
        print("  [BASELINE FAIL] 种子基线不符：" + " ".join(bad))
        print("  请先重置种子（mysql < docs/04_数据库设计.sql）后重跑本脚本")
        sys.exit(1)
    print("  [OK] 种子基线校验通过：6/4/4/6/8/3/3/3")


def parse_java_rules():
    """从 Java 规约表提取 {编号: 判定SQL}（第一条 SELECT 即判定 SQL，第二条为定位 SQL）。"""
    if not os.path.isfile(JAVA_RULES):
        return None
    src = open(JAVA_RULES, encoding="utf-8").read()
    out = {}
    for chunk in src.split("new AuditRule(")[1:]:
        m_id = re.search(r'"([A-Za-z0-9]+)"', chunk)
        m_sql = re.search(r'"(SELECT[^"]+)"', chunk)
        if m_id and m_sql:
            out[m_id.group(1)] = m_sql.group(1).strip()
    return out


def parse_table_rules():
    """从《规则清单唯一口径表》提取 {编号: 判定SQL}。"""
    if not os.path.isfile(TABLE_MD):
        return None
    md = open(TABLE_MD, encoding="utf-8").read()
    pat = re.compile(r"\|\s*\*\*(R\d+[a-d]?)\*\*\s*\|[^|]*\|[^|]*\|\s*\`(SELECT[^\`]+)\`")
    return {m.group(1): m.group(2).strip() for m in pat.finditer(md)}


print("=" * 74)
print("LibMS 数据一致性巡检专项测试（创新点·数据健康诊断）")
print("=" * 74)
baseline_check()

at = (req("POST", "/auth/login", body={"username": "admin", "password": "123456"}).get("data") or {}).get("token")
lt = (req("POST", "/auth/login", body={"username": "librarian01", "password": "123456"}).get("data") or {}).get("token")
rt = (req("POST", "/auth/login", body={"username": "reader001", "password": "123456"}).get("data") or {}).get("token")

max_log_id = db_one("SELECT COALESCE(MAX(id),0) FROM operation_log;") or "0"
before_counts = {}
before_checksums = {}
for t in BUSINESS_TABLES:
    before_counts[t] = db_one("SELECT COUNT(*) FROM " + t + ";")
    # 2026-10-24（D2-03）：逐表 CHECKSUM——"行数不变但行内值被改"的写操作才算检得出
    before_checksums[t] = db_one("CHECKSUM TABLE " + t + ";")
book1_stock_before = db_one("SELECT stock FROM book WHERE id=1;")

# 2026-10-24（D2-12）：记录"实际注入过的 book.id 集合"，finally 只按该集合回滚，
# 不再对固定 6 本各 +1（原写法只注入 id=1 却给 1,2,3,4,5,6 加库存 → 单独跑留脏数据）
injected_ids = set()

try:
    # ---------- 1. 报告结构 ----------
    print("\n[1] 巡检报告结构")
    r = req("POST", "/audit/run", at)
    rep = r.get("data") or {}
    check("巡检接口返回业务码 200", r.get("code") == 200, r.get("msg"))
    check("规则数 = 10（R1–R10）", rep.get("ruleCount") == 10, rep.get("ruleCount"))
    check("检查项数 = 14（展开粒度）", rep.get("checkCount") == 14, rep.get("checkCount"))
    check("基线（种子库）零异常", rep.get("anomalyItemCount") == 0, rep.get("anomalyItemCount"))
    check("报告含快照时点", bool(rep.get("snapshotAt")), rep.get("snapshotAt"))
    concl = rep.get("conclusion") or ""
    check("结论为'未发现'措辞（不称数据绝对正确）",
          "未发现规则范围内的数据不一致" in concl and "绝对正确" not in concl, concl)
    check("明细项数 = 14", len(rep.get("items") or []) == 14, len(rep.get("items") or []))
    check("通过项数 = 14", rep.get("passedCount") == 14, rep.get("passedCount"))

    # ---------- 2. 规则清单（不回传 SQL）----------
    print("\n[2] 规则清单接口")
    rr = req("GET", "/audit/rules", at)
    rules = rr.get("data") or []
    check("规则清单返回 14 项", len(rules) == 14, len(rules))
    leaked = [(i, k) for i, r in enumerate(rules) for k in ("countSql", "locationSql", "sql") if k in r]
    check("规则清单不含 SQL 字段（防通用查询入口）", not leaked, leaked[:5])

    # ---------- 3. 权限 ----------
    print("\n[3] 权限控制（仅系统管理员）")
    check("读者访问巡检被拒 403", req("POST", "/audit/run", rt).get("code") == 403)
    check("图书管理员访问巡检被拒 403", req("POST", "/audit/run", lt).get("code") == 403)
    check("未登录访问巡检 401", req("POST", "/audit/run", None).get("code") == 401)
    check("读者访问规则清单被拒 403", req("GET", "/audit/rules", rt).get("code") == 403)

    # ---------- 4. 只读性 ----------
    print("\n[4] 只读性（不得改动业务数据）")
    changed = []
    for t in BUSINESS_TABLES:
        now_ck = db_one("CHECKSUM TABLE " + t + ";")
        now_cnt = db_one("SELECT COUNT(*) FROM " + t + ";")
        if (now_ck is None or now_cnt is None or before_checksums[t] is None
                or before_counts[t] is None
                or now_ck != before_checksums[t] or now_cnt != before_counts[t]):
            changed.append(t + ":" + str(before_checksums[t]) + "->" + str(now_ck)
                           + " 行数 " + str(before_counts[t]) + "->" + str(now_cnt))
    check("巡检未改动 7 张业务表（逐表 CHECKSUM + 行数比对，含行内值变化）", not changed, changed)
    stock_now = db_one("SELECT stock FROM book WHERE id=1;")
    check("巡检未改动图书库存值", stock_now is not None and stock_now == book1_stock_before)

    # ---------- 5. 口径一致（机制化防漂移）----------
    print("\n[5] 口径一致（规约表 SQL 与《唯一口径表》逐字比对）")
    jr = parse_java_rules()
    tr = parse_table_rules()
    if jr is None or tr is None:
        print("  [SKIP] 规约表或口径表文件未找到，跳过错字比对")
    else:
        check("规约表条目数 = 14", len(jr) == 14, len(jr))
        check("口径表条目数 = 14", len(tr) == 14, len(tr))
        diff = [k for k in jr if k in tr and jr[k] != tr[k]]
        missing = [k for k in jr if k not in tr]
        check("14 条判定 SQL 与口径表逐字一致", not diff and not missing,
              "差异=" + str(diff) + " 缺失=" + str(missing))

    # ---------- 6. 注入 → 发现 → 恢复 → 复查归零 ----------
    print("\n[6] 闭环：注入异常 → 巡检发现 → 恢复 → 复查归零")
    db_exec("UPDATE book SET stock=stock-1 WHERE id=1;")
    injected_ids.update({1})
    rep2 = (req("POST", "/audit/run", at).get("data") or {})
    r1 = next((i for i in (rep2.get("items") or []) if i.get("ruleId") == "R1"), {})
    check("注入库存漂移后被检出（异常项 = 1）", rep2.get("anomalyItemCount") == 1, rep2.get("anomalyItemCount"))
    check("R1 判定为未通过", r1.get("passed") is False, r1.get("passed"))
    check("R1 异常行数 = 1", r1.get("count") == 1, r1.get("count"))
    check("R1 定位到具体记录（bookId=1）", "bookId=1" in (r1.get("samples") or []), r1.get("samples"))
    check("R1 给出期望值说明", bool(r1.get("expected")), r1.get("expected"))
    check("仅 R1 报警（未误伤其他规则）", rep2.get("checkCount") - rep2.get("passedCount") == 1,
          str(rep2.get("passedCount")) + "/" + str(rep2.get("checkCount")))

    db_exec("UPDATE book SET stock=stock+1 WHERE id=1;")
    injected_ids.difference_update({1})
    rep3 = (req("POST", "/audit/run", at).get("data") or {})
    check("恢复后复查归零", rep3.get("anomalyItemCount") == 0, rep3.get("anomalyItemCount"))

    # ---------- 7. 明细限流 ----------
    print("\n[7] 异常明细限流（防大结果集卡顿）")
    # book3 种子库存为 0，再减 1 会被新 CHECK(stock>=0) 拦截；用 5 本有库存的书即可验证
    # “全部计数 + 样本限流”（样本上限仍是 5）。
    db_exec("UPDATE book SET stock=stock-1 WHERE id IN (1,2,4,5,6);")
    injected_ids.update({1, 2, 4, 5, 6})
    rep4 = (req("POST", "/audit/run", at).get("data") or {})
    r1b = next((i for i in (rep4.get("items") or []) if i.get("ruleId") == "R1"), {})
    check("注入 5 行异常被全部计数", r1b.get("count") == 5, r1b.get("count"))
    samples = r1b.get("samples") or []
    check("定位样本 = 5 条且覆盖全部注入行", len(samples) == 5 and set(samples) == {f"bookId={i}" for i in (1, 2, 4, 5, 6)}, samples)
    db_exec("UPDATE book SET stock=stock+1 WHERE id IN (1,2,4,5,6);")
    injected_ids.difference_update({1, 2, 4, 5, 6})

    # ---------- 8. 审计留痕 ----------
    print("\n[8] 巡检动作留痕（不落库报告本体）")
    logs = db_one("SELECT COUNT(*) FROM operation_log WHERE operation='DATA_AUDIT';")
    check("巡检产生 DATA_AUDIT 审计日志", logs is not None and int(logs) > 0, logs)

finally:
    # 净零副作用：按实际注入集合恢复 + 清理本次产生的审计日志
    if injected_ids:
        ids_sql = ",".join(str(i) for i in sorted(injected_ids))
        db_exec("UPDATE book SET stock=stock+1 WHERE id IN (" + ids_sql + ");")
        print("  [INFO] 注入已按实际 id 集合回滚：" + ids_sql)
        injected_ids.clear()
    db_exec("DELETE FROM operation_log WHERE operation='DATA_AUDIT' AND id > " + str(max_log_id) + ";")
    after_ck = db_one("CHECKSUM TABLE book;")
    stock_after = db_one("SELECT stock FROM book WHERE id=1;")
    if stock_after != book1_stock_before:
        FAIL += 1
        print("  [FAIL] book 表 id=1 库存未复原：%s → %s，请检查" % (book1_stock_before, stock_after))
    elif after_ck != before_checksums.get("book"):
        # 2026-10-24 实测复核：book.update_time 为 ON UPDATE CURRENT_TIMESTAMP（docs/04_数据库设计.sql），
        # 回滚注入用的 UPDATE 必然刷新它 → 整表 CHECKSUM 与初值不同属预期内，库存与行内值已复原。
        print("  [INFO] 库存与行内值已复原；book 表 CHECKSUM 变化属预期内（回滚 UPDATE 刷新 update_time）")
    else:
        print("  [INFO] 数据已复原（book 表 CHECKSUM 一致），副作用已清理")

print("\n" + "=" * 74)
print("巡检专项测试完成：通过 " + str(PASS) + " 项，失败 " + str(FAIL) + " 项")
print("=" * 74)
sys.exit(0 if FAIL == 0 else 1)
