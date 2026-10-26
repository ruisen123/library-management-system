# -*- coding: utf-8 -*-
"""LibMS 边界输入回归测试（第十八轮 M-14 沉淀为常规资产）

覆盖三类边界（docs/40 元审查列出的"四类边界输入"去掉浏览器层一类）：
  ① 可空字段传空串（≠ 省略字段）—— 第七轮 "" 占唯一索引位 / 第九轮 R9-1 空值兜底 的回归防线
  ② 超长值跨列宽 —— title 超长 / 超长 keyword / 超长 X-Forwarded-For（防"外部可控值入库"炸库）
  ③ 异常 token —— 超长 / 伪造
第 ④ 类"伪造 localStorage 态"属浏览器层，见 %TEMP%\\pwcheck\\refresh_fix.js 的 C4-C7 用例。

用法（**必须先重置种子**）：
    mysql -u root -p --default-character-set=utf8mb4 < docs/04_数据库设计.sql
    python test/boundary_test.py
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")  # 可用环境变量覆盖（默认本地测试口令，见运行手册）


def db_one(sql):
    """直查数据库返回单值；mysql 不可用返回 None（边界断言优雅 SKIP）。"""
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
    """种子基线校验（M-4）：防"上一轮脚本污染未重置"导致的误判。"""
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
        print("  请先重置种子（mysql < docs/04_数据库设计.sql）后重跑本脚本")
        sys.exit(1)
    print("  [OK] 种子基线校验通过：6/4/4/6/8/3/3/3")


def req(method, path, token=None, body=None, headers=None):
    """返回 (http_status, body_dict)；body 非 JSON 时为空字典。"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("token", token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        resp = urllib.request.urlopen(r, timeout=15)
        try:
            return resp.status, json.loads(resp.read().decode("utf-8"))
        except Exception:
            return resp.status, {}
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}
    except Exception as e:
        return -1, {"code": -1, "msg": "EXC:" + str(e)}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


print("=" * 72)
print("LibMS 边界输入回归测试（空串归一 / 超长值 / 异常头 / 分页越界）")
print("=" * 72)
baseline_check()  # 必须在任何请求（含登录）之前：登录会写 LOGIN 日志、破坏 operation_log 基线

_, _b = req("POST", "/auth/login", body={"username": "admin", "password": "123456"})
AT = (_b.get("data") or {}).get("token")
_, _b = req("POST", "/auth/login", body={"username": "librarian01", "password": "123456"})
LT = (_b.get("data") or {}).get("token")

# ============ [1] 可空字段传空串（"" 归一为 null）============
print("\n[1] 空串归一（第七轮 / 第九轮修复回归防线）")
_, b = req("POST", "/users", AT, {"username": "bdu1", "realName": "边界一", "role": "READER", "readerNo": ""})
check("1.1 建用户 readerNo='' 成功（归一 null 不占唯一位）", b.get("code") == 200, str(b.get("msg"))[:40])
_, b = req("POST", "/users", AT, {"username": "bdu2", "realName": "边界二", "role": "READER", "readerNo": ""})
check("1.2 第二个 readerNo='' 用户仍成功（'' 未占用唯一索引位）", b.get("code") == 200, str(b.get("msg"))[:40])

_, b = req("POST", "/categories", AT, {"name": "bd_cat", "sort": 7})
check("1.3 建分类（sort=7）", b.get("code") == 200, str(b.get("msg"))[:40])
_, cl = req("GET", "/categories", AT)
CID = next((c["id"] for c in (cl.get("data") or []) if c.get("name") == "bd_cat"), None)
_, b = req("PUT", f"/categories/{CID}", AT, {"name": "bd_cat"})  # 省略 sort
_, cl = req("GET", "/categories", AT)
sort_omitted = next((c.get("sort") for c in (cl.get("data") or []) if c["id"] == CID), "N/A")
check("1.4 编辑省略 sort 保留原值 7（R9-1 反向用例）",
      b.get("code") == 200 and sort_omitted == 7, f"code={b.get('code')} sort={sort_omitted}")
_, b = req("PUT", f"/categories/{CID}", AT, {"name": "bd_cat", "sort": ""})  # 空串 sort
_, cl = req("GET", "/categories", AT)
sort_empty = next((c.get("sort") for c in (cl.get("data") or []) if c["id"] == CID), "N/A")
check("1.5 编辑 sort='' 同样保留原值 7（空串面）",
      b.get("code") == 200 and sort_empty == 7, f"code={b.get('code')} sort={sort_empty}")

# ============ [2] 超长值 ============
print("\n[2] 超长值（跨列宽）")
_, b = req("POST", "/books", LT, {"isbn": "9780000000301", "title": "长" * 201, "author": "x",
                                  "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
check("2.1 title=201 字被拒（@Size 校验先于列宽）", b.get("code") == 400, str(b.get("msg"))[:50])
_, b = req("GET", "/books?keyword=" + quote("长" * 300), LT)
check("2.2 中文 300 字 keyword 查询正常（200）", b.get("code") == 200, str(b.get("msg"))[:40])
_, b = req("GET", "/books?keyword=" + quote("%"), LT)
# LIKE 通配符必须按字面量处理：种子库无含 % 的书名/作者/ISBN，命中应为 0；
# 若转义失效，% 会命中全表（6 本）→ 本断言转红。
_like_total = (b.get("data") or {}).get("total")
check("2.3 LIKE 通配符按字面量处理（% 不全表命中）",
      b.get("code") == 200 and _like_total == 0,
      f"code={b.get('code')} total={_like_total}")
_, b = req("POST", "/auth/login", body={"username": "admin", "password": "123456"},
           headers={"X-Forwarded-For": "10.0.0.1, " * 30})
check("2.4 超长 X-Forwarded-For 登录成功（不炸库）", b.get("code") == 200, str(b.get("msg"))[:40])
ip_row = db_one("SELECT ip FROM operation_log WHERE operation='LOGIN' ORDER BY id DESC LIMIT 1;")
check("2.5 本次超长 XFF 登录已留痕且 IP 未被采信（长度 ≤ 50 列宽）",
      ip_row is not None and ip_row != "" and not ip_row.startswith("10.0.0.1") and len(ip_row) <= 50,
      f"ip={ip_row}")

# ============ [3] 异常 token ============
print("\n[3] 异常 token")
_c, b = req("GET", "/profile", "A" * 5000)
check("3.1 超长 token 拒绝（401，不崩）", b.get("code") == 401, f"code={b.get('code')}")
_c, b = req("GET", "/profile", "eyJhbGciOiJIUzI1NiJ9.fake.sig")
check("3.2 伪造 token 拒绝（401）", b.get("code") == 401, f"code={b.get('code')}")

# ============ [4] 分页越界 ============
print("\n[4] 分页越界（不崩原则）")
_c, b = req("GET", "/books?page=0&size=10", LT)
_recs = ((b.get("data") or {}).get("records") or [])
check("4.1 page=0 归一为第 1 页", b.get("code") == 200 and 0 < len(_recs) <= 10,
      f"code={b.get('code')} n={len(_recs)}")
_c, b = req("GET", "/books?page=9999&size=10", LT)
check("4.2 page=9999 返回空列表", b.get("code") == 200 and (b.get("data") or {}).get("records") == [],
      f"code={b.get('code')} records={(b.get('data') or {}).get('records')}")
_c, b = req("GET", "/books?page=1&size=10000", LT)
_d = b.get("data") or {}
check("4.3 size=10000 收敛到上限 1000", b.get("code") == 200 and int(_d.get("size") or 0) == 1000,
      f"code={b.get('code')} size={_d.get('size')}")
_c, b = req("GET", "/books?page=-1&size=10", LT)
_recs = ((b.get("data") or {}).get("records") or [])
check("4.4 page=-1 归一为第 1 页", b.get("code") == 200 and 0 < len(_recs) <= 10,
      f"code={b.get('code')} n={len(_recs)}")
_c, b = req("GET", "/books?page=1&size=-1", LT)
_recs = ((b.get("data") or {}).get("records") or [])
check("4.5 size=-1 归一为 1", b.get("code") == 200 and len(_recs) == 1,
      f"code={b.get('code')} n={len(_recs)}")
_c, b = req("GET", "/books?page=1&size=0", LT)
_recs = ((b.get("data") or {}).get("records") or [])
check("4.6 size=0 归一为 1", b.get("code") == 200 and len(_recs) == 1,
      f"code={b.get('code')} n={len(_recs)}")

# ============ [4b] 错误路径语义（404 一致性，R19-5 回归防线）============
print("\n[4b] 错误路径语义（不存在记录的 404 一致性）")
_c, b = req("POST", "/renew", LT, {"recordId": 999999})
check("4b.1 续借不存在记录 → 404", b.get("code") == 404, f"code={b.get('code')}")
_c, b = req("POST", "/fines/compensate", LT, {"recordId": 999999, "amount": 1, "type": 2})
check("4b.2 赔偿不存在记录 → 404（R19-5 修复回归防线）", b.get("code") == 404, f"code={b.get('code')}")
_c, b = req("PUT", "/fines/999999/pay", LT)
check("4b.3 缴费不存在罚金 → 404（R20-2 修复回归防线）", b.get("code") == 404, f"code={b.get('code')}")

# ============ [5] 清理探针数据 ============
print("\n[5] 清理本脚本数据")
for kw in ("bdu1", "bdu2"):
    _, bl = req("GET", f"/users?keyword={kw}", AT)
    for rec in ((bl.get("data") or {}).get("records") or []):
        req("DELETE", f"/users/{rec['id']}", AT)
if CID:
    req("DELETE", f"/categories/{CID}", AT)
_, bl = req("GET", "/users?keyword=bdu", AT)
_, cl = req("GET", "/categories", AT)
left_users = len(((bl.get("data") or {}).get("records") or []))
left_cat = len([c for c in (cl.get("data") or []) if c.get("name") == "bd_cat"])
check("5.1 临时用户与分类清理完毕（净零副作用）", left_users == 0 and left_cat == 0,
      f"users={left_users} cat={left_cat}")

print("\n" + "=" * 72)
print(f"边界测试完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 72)
sys.exit(0 if FAIL == 0 else 1)  # M-3：失败时退出码非 0
