
# -*- coding: utf-8 -*-
"""LibMS 接口性能压测（test/perf）——产出论文表 6-4 与表 6-5 的可复现数据

用法：
  set LIBMS_DB_PWD=***           # 必填：测试库口令
  python test/perf/bench_api.py
  python test/perf/bench_api.py --dry-run

口径（与论文 6.3 一致）：
  表 6-4  接口响应时间：每场景预热 5 次后采样 20 次取均值；万级场景先灌 1 万图书 + 5 万借阅记录
  表 6-5  并发容量：每接口 300 次，并发 10 与 50 两档，累计 2400 次；用演示种子数据量

净零副作用：万级数据灌在 library_db 上，测后 reset_seed 恢复演示数据。

踩坑备忘（写脚本时别重犯）：
  1. MySQL 的 LIMIT/OFFSET 不接受表达式，定位行要用 MIN(id)+取模
  2. borrow_record 是流水表，没有 deleted 列
  3. **必须按接口的实际授权角色取令牌**：/stats、/users 仅 ADMIN；
     /books、/borrow-records 仅 LIBRARIAN(+READER)；用 admin 测这些接口拿到的是 403，
     测出来的 0.0x ms 是"被拒绝"的耗时，不是查询耗时
  4. 每个场景必须校验返回条数，空结果集会测出假快
"""
import json, os, statistics, subprocess, sys, threading, time, shutil
import urllib.request, urllib.error, urllib.parse
from concurrent.futures import ThreadPoolExecutor

sys.stdout.reconfigure(encoding='utf-8')

_HERE = os.path.dirname(os.path.abspath(__file__))
_TEST_DIR = os.path.dirname(_HERE)
sys.path.insert(0, _TEST_DIR)
try:
    import _common as C
    ROOT = C.PROJECT_ROOT
except Exception as _e:
    print("ERROR: 无法导入 test/_common.py: %r" % _e)
    sys.exit(2)

BASE = "http://localhost:8080"
DRY = "--dry-run" in sys.argv
PWD_ = os.environ.get("LIBMS_DB_PWD")
MYSQL = os.environ.get("LIBMS_MYSQL") or shutil.which("mysql")
TOKENS = {}

def req(method, path, token=None, body=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    # URL 里的中文必须百分号编码，否则 urllib 直接抛异常（会伪装成 -1 的"超快"）
    safe_path = urllib.parse.quote(path, safe="/?=&%:,")
    r = urllib.request.Request(BASE + safe_path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token: r.add_header("token", token)
    t0 = time.perf_counter()
    try:
        resp = urllib.request.urlopen(r, timeout=timeout)
        raw = resp.read()
        ms = (time.perf_counter() - t0) * 1000.0
        try: j = json.loads(raw.decode("utf-8"))
        except Exception: j = None
        return (resp.status, ms, j)
    except urllib.error.HTTPError as e:
        e.read()
        return (e.code, (time.perf_counter() - t0) * 1000.0, None)
    except Exception:
        return (-1, (time.perf_counter() - t0) * 1000.0, None)

def rowcount(j):
    if not isinstance(j, dict): return None
    d = j.get("data")
    if isinstance(d, dict):
        if "total" in d: return d.get("total")
        if isinstance(d.get("records"), list): return len(d["records"])
    if isinstance(d, list): return len(d)
    return None

def db(sql, timeout=900):
    cmd = [MYSQL, "-uroot", "-p" + PWD_, "--default-character-set=utf8mb4", "-N", "-B", "library_db"]
    r = subprocess.run(cmd, input=sql, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout)
    return r.stdout.strip(), r.returncode, (r.stderr or "").strip()

def login_as(username, password="123456"):
    if username in TOKENS: return TOKENS[username]
    data = json.dumps({"username": username, "password": password}).encode()
    r = urllib.request.Request(BASE + "/auth/login", data=data, method="POST")
    r.add_header("Content-Type", "application/json")
    tok = (json.loads(urllib.request.urlopen(r, timeout=30).read().decode()).get("data") or {}).get("token")
    TOKENS[username] = tok
    return tok

# (名称, method, path, body, 数据量标记, 角色)
SCEN = [
    ("用户登录",            "POST", "/auth/login", {"username": "admin", "password": "123456"}, "常规", "NONE"),
    ("用户列表分页",         "GET", "/users?page=1&size=10", None, "常规", "ADMIN"),
    ("月度借阅趋势聚合",      "GET", "/stats/trend", None, "万级", "ADMIN"),
    ("借阅热度排行（TOP10）", "GET", "/stats/top", None, "万级", "ADMIN"),
    ("用户角色/状态筛选",     "GET", "/users?page=1&size=10&role=READER", None, "常规", "ADMIN"),
    ("在借记录查询",         "GET", "/borrow-records?page=1&size=10", None, "万级", "LIBRARIAN"),
    ("图书关键词（中缀）检索", "GET", "/books?page=1&size=10&keyword=性能测试", None, "万级", "LIBRARIAN"),
]
CONC = [
    ("用户登录",     "POST", "/auth/login", {"username": "admin", "password": "123456"}, "NONE"),
    ("图书列表查询",  "GET", "/books?page=1&size=10", None, "LIBRARIAN"),
    ("借阅记录查询",  "GET", "/borrow-records?page=1&size=10", None, "LIBRARIAN"),
    ("统计总览查询",  "GET", "/stats/overview", None, "ADMIN"),
]

def fill_scale():
    sql = ["SET FOREIGN_KEY_CHECKS=0;"]
    sql.append("""INSERT INTO book (isbn,title,author,category_id,publisher_id,publish_date,price,total_stock,stock,location,description,deleted)
SELECT CONCAT('9787',LPAD(n,9,'0')), CONCAT('性能测试图书',n), CONCAT('作者',n%500), (n%4)+1, (n%4)+1,
       DATE_ADD('2015-01-01', INTERVAL (n%3600) DAY), ROUND(20+(n%80),2), 5, 5, CONCAT('P-',n%400), '性能测试数据', 0
FROM (SELECT a.N + b.N*10 + c.N*100 + d.N*1000 + 1 AS n
      FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
           (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
           (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
           (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d) t
WHERE NOT EXISTS (SELECT 1 FROM book b WHERE b.isbn = CONCAT('9787',LPAD(t.n,9,'0')));""")
    sql.append("SET @b0 = (SELECT MIN(id) FROM book WHERE deleted=0);")
    sql.append("SET @u0 = (SELECT MIN(id) FROM sys_user WHERE role='READER' AND deleted=0);")
    sql.append("""INSERT INTO borrow_record (book_id,user_id,borrow_date,due_date,return_date,status,renew_count)
SELECT @b0 + (t.n % 9000), @u0 + (t.n % 4),
       DATE_SUB('2026-10-01', INTERVAL (t.n%720) DAY),
       DATE_SUB('2026-10-01', INTERVAL (t.n%720) DAY) + INTERVAL 30 DAY,
       DATE_SUB('2026-10-01', INTERVAL (t.n%720) DAY) + INTERVAL 25 DAY,
       2, 0
FROM (SELECT a.N + b.N*10 + c.N*100 + d.N*1000 + e.N*10000 + 1 AS n
      FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
           (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
           (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
           (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
           (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) e) t;""")
    sql.append("SET FOREIGN_KEY_CHECKS=1;")
    out, rc, err = db("\n".join(sql))
    cnt_b, _, _ = db("SELECT COUNT(*) FROM book;")
    cnt_r, _, _ = db("SELECT COUNT(*) FROM borrow_record;")
    return rc == 0, cnt_b, cnt_r, err

def pct(vals, p):
    s = sorted(vals)
    if not s: return 0.0
    k = max(0, min(len(s) - 1, int(round((p / 100.0) * len(s) + 0.5)) - 1))
    return s[k]

def tok_for(role):
    if role == "NONE": return None
    if role == "ADMIN": return login_as("admin")
    if role == "LIBRARIAN": return login_as("librarian01")
    if role == "READER": return login_as("reader001")
    return None

def bench_scen(tag):
    out = []
    for name, m, p, b, t, role in SCEN:
        if t != tag: continue
        tok = tok_for(role)
        for _ in range(5): req(m, p, token=tok, body=b)
        vals, cnt, code = [], None, None
        for _ in range(20):
            st, ms, j = req(m, p, token=tok, body=b)
            vals.append(ms); cnt = rowcount(j); code = st
        # 2026-10-24 R3 修复：分页类场景必须真拿到 total，否则说明路径不存在/被兜成 404 体，
        # 此时继续采样只会产出"看起来很漂亮的假数据"（历史事故：/borrow/borrow-records 4.79ms）。
        if cnt is None and "page=" in p:
            print("  [FAIL] %-22s 返回条数 None（HTTP %s）—— 路径疑似不存在或非分页响应，拒绝产出无效数据" % (name, code))
            sys.exit(3)
        out.append((name, t, statistics.mean(vals), cnt, code))
        print("  %-22s 均值 %8.2f ms   HTTP %s   返回条数 %s" % (name, statistics.mean(vals), code, cnt))
    return out

def main():
    print("=" * 78)
    print("LibMS 接口性能压测（产出论文表 6-4 / 表 6-5）")
    print("=" * 78)
    if DRY:
        print("[dry-run] reset_seed → 灌万级 → 7 场景 → 并发 10/50 → reset_seed")
        for n, m, p, b, tag, r in SCEN: print("  %-22s %-4s %-45s %s 角色=%s" % (n, m, p, tag, r))
        for n, m, p, b, r in CONC: print("  %-22s %-4s %-45s 角色=%s" % (n, m, p, r))
        return 0
    if not PWD_:
        print("ERROR: 未设置 LIBMS_DB_PWD"); return 2

    ok, msg = C.reset_seed()
    print("[准备] 种子重置：%s" % msg)
    TOKENS.clear()
    for u in ("admin", "librarian01"):
        print("[准备] %s 登录：%s" % (u, "OK" if login_as(u) else "FAIL"))

    print("\n[1] 响应时间（常规，预热 5 + 采样 20）")
    rows = bench_scen("常规")

    print("\n[2] 灌入万级（1 万图书 + 5 万借阅记录）…")
    ok2, nb, nr, err = fill_scale()
    print("  book=%s borrow_record=%s  ok=%s%s" % (nb, nr, ok2, ("" if ok2 else "  ERR=" + err[:200])))
    print("\n[3] 响应时间（万级，预热 5 + 采样 20）")
    rows += bench_scen("万级")

    ok3, msg3 = C.reset_seed()
    print("\n[4] 恢复演示数据：%s" % msg3)
    TOKENS.clear()
    for u in ("admin", "librarian01"): login_as(u)

    print("\n[5] 并发容量（每接口 300 次，并发 10 / 50）")
    conc_rows = []
    for conc_n in (10, 50):
        for name, m, p, b, role in CONC:
            tok = tok_for(role); N = 300
            lat, err = [], [0]; lock = threading.Lock()
            def worker(_i):
                st, ms, _j = req(m, p, token=tok, body=b)
                with lock:
                    lat.append(ms)
                    if st != 200: err[0] += 1
            t0 = time.perf_counter()
            with ThreadPoolExecutor(max_workers=conc_n) as ex:
                list(ex.map(worker, range(N)))
            dur = time.perf_counter() - t0
            conc_rows.append((conc_n, name, N / dur if dur > 0 else 0, statistics.mean(lat),
                              pct(lat, 95), pct(lat, 99), err[0]))
            print("  并发%-3d %-20s QPS %8.1f  P95 %7.1f ms  P99 %7.1f ms  错误 %d"
                  % (conc_n, name, N / dur if dur > 0 else 0, pct(lat, 95), pct(lat, 99), err[0]))

    ok4, msg4 = C.reset_seed()
    print("\n[清理] 种子重置：%s" % msg4)

    print("\n" + "=" * 78)
    print("## 表 6-4　接口响应时间实测结果")
    print("| 测试场景 | 数据量 | 实测耗时 | HTTP | 返回条数 | 需求指标 | 结果 |")
    print("|----------|:---:|:---:|:---:|:---:|:---:|:---:|")
    for n, tag, ms, cnt, code in rows:
        print("| %s | %s | %.1f ms | %s | %s | ≤ 500 ms | %s |" %
              (n, tag, ms, code, cnt, "达标" if (ms <= 500 and code == 200) else "未达标"))
    print("\n## 表 6-5　并发容量实测结果")
    print("| 并发 | 接口 | QPS | 均值 | P95 | P99 | 错误数 |")
    print("|:---:|------|:---:|:---:|:---:|:---:|:---:|")
    for c, n, qps, mean, p95, p99, e in conc_rows:
        print("| %d | %s | %.1f | %.1f ms | %.1f ms | %.1f ms | %d |" % (c, n, qps, mean, p95, p99, e))
    print("=" * 78)
    return 0

if __name__ == "__main__":
    sys.exit(main())
