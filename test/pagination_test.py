# -*- coding: utf-8 -*-
"""LibMS 分页正确性回归测试（第二十二轮新增常驻套件）

沉淀"分页集合一致性"防线（此前回归未系统覆盖）：
  [1] 全量拉取 vs 多尺寸分页遍历的 id 集合一致性（检测分页遗漏/重复）
  [2] 带筛选条件下的分页一致性
  [3] 分页边界（末页不完整 / 越界页空）
本套件只读，零副作用；判定以退出码为准。
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")


def db_one(sql):
    for cmd in ("mysql", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"):
        try:
            r = subprocess.run([cmd, "-uroot", "-p" + DB_PWD, "-N", "-e", "USE library_db; " + sql],
                               capture_output=True, text=True, encoding="utf-8",
                               errors="replace", timeout=15)
            if r.returncode == 0:
                return r.stdout.strip()
        except Exception:
            continue
    return None


def baseline_check():
    expect = {"sys_user": 6, "category": 4, "publisher": 4, "book": 6,
              "borrow_record": 8, "fine_record": 3, "notice": 3, "operation_log": 3}
    bad = []
    for table, want in expect.items():
        got = db_one(f"SELECT COUNT(*) FROM {table};")
        if got is None:
            print("  [SKIP] mysql 不可用，跳过种子基线校验")
            return
        if got != str(want):
            bad.append(f"{table}={got}(期望{want})")
    if bad:
        print(f"  [BASELINE FAIL] 种子基线不符：{' '.join(bad)}")
        print("  请先重置种子（mysql < docs/04_数据库设计.sql）后重跑")
        sys.exit(1)
    print("  [OK] 种子基线校验通过：6/4/4/6/8/3/3/3")


def req(path, token=None):
    r = urllib.request.Request(BASE + path, method="GET")
    if token:
        r.add_header("token", token)
    try:
        resp = urllib.request.urlopen(r, timeout=20)
        return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            return {}
    except Exception as e:
        return {"code": -1, "msg": str(e)}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def paged_ids(path, token, size):
    """逐页拉取直到空/不完整，返回 (id 列表, 页数)。"""
    ids, page = [], 1
    while page <= 200:
        sep = "&" if "?" in path else "?"
        b = req(f"{path}{sep}page={page}&size={size}", token)
        recs = (b.get("data") or {}).get("records") or []
        if not recs:
            break
        ids += [r.get("id") for r in recs]
        if len(recs) < size:
            break
        page += 1
    return ids, page


print("=" * 72)
print("LibMS 分页正确性回归测试（集合一致性 / 筛选 / 边界）")
print("=" * 72)
baseline_check()

r = urllib.request.Request(BASE + "/auth/login", data=json.dumps({"username": "admin", "password": "123456"}).encode(), method="POST")
r.add_header("Content-Type", "application/json")
AT = (json.loads(urllib.request.urlopen(r, timeout=15).read().decode("utf-8")).get("data") or {}).get("token")
r2 = urllib.request.Request(BASE + "/auth/login", data=json.dumps({"username": "librarian01", "password": "123456"}).encode(), method="POST")
r2.add_header("Content-Type", "application/json")
LT = (json.loads(urllib.request.urlopen(r2, timeout=15).read().decode("utf-8")).get("data") or {}).get("token")

# ============ [1] 分页集合一致性 ============
print("\n[1] 全量 vs 分页遍历（size=2）集合一致性")
for label, path, tk in [("books", "/books", LT), ("users", "/users", AT),
                        ("notices", "/notices", AT), ("borrow-records", "/borrow-records", LT),
                        ("logs", "/logs", AT)]:
    full, _ = paged_ids(path, tk, 200)
    small, pages = paged_ids(path, tk, 2)
    dup = len(small) != len(set(small))
    # 空集合会让 set 相等恒真（2026-10-24 R3-D2）：必须同时断言全量非空，
    # 否则接口挂了/返回空页时这条反而"通过"
    check(f"1 {label}（全量 {len(full)} / 分页 {len(small)} 共 {pages} 页）集合一致且无重复",
          len(full) > 0 and set(full) == set(small) and not dup,
          f"full={len(full)} small={len(small)} dup={dup}")

# ============ [2] 筛选分页一致性 ============
print("\n[2] 筛选条件下的分页一致性")
f_full, _ = paged_ids("/books?categoryId=1", LT, 200)
f_one, pages1 = paged_ids("/books?categoryId=1", LT, 1)
check(f"2.1 books?categoryId=1（全量 {len(f_full)} / size=1 遍历 {len(f_one)} 共 {pages1} 页）一致",
      set(f_full) == set(f_one) and len(f_full) > 0, f"full={len(f_full)} one={len(f_one)}")

# ============ [3] 分页边界 ============
print("\n[3] 分页边界")
b = req("/books?page=1&size=3", LT)
d = b.get("data") or {}
check("3.1 首页完整（size=3 → 返回 3 条，total 与实际一致）",
      len(d.get("records") or []) == 3 and d.get("total") == 6, f"records={len(d.get('records') or [])} total={d.get('total')}")
b2 = req("/books?page=2&size=3", LT)
d2 = b2.get("data") or {}
check("3.2 末页不完整（size=3 第 2 页 → 返回剩余 3 条，页数=2）",
      len(d2.get("records") or []) == 3 and d2.get("pages") == 2, f"records={len(d2.get('records') or [])} pages={d2.get('pages')}")
b3 = req("/books?page=99&size=3", LT)
d3 = b3.get("data") or {}
check("3.3 越界页返回空列表（code=200 且 records 为空）",
      b3.get("code") == 200 and (d3.get("records") or []) == [], f"code={b3.get('code')}")

print("\n" + "=" * 72)
print(f"分页正确性测试完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 72)
sys.exit(0 if FAIL == 0 else 1)
