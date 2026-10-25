# -*- coding: utf-8 -*-
"""LibMS 推荐项回归：热门借阅排行（5.2）+ 读者额度管理（5.3）

覆盖：
  [1] 热门排序：累计借阅次数降序、同分按 id 降序、分页稳定
  [2] 组合条件：关键词 / 分类 / onlyStock 与热门排序组合
  [3] 权限与边界：三角色可读、非法 sort 拒绝、逻辑删除图书不出现、默认排序不变
  [4] 读者额度：默认 5、自定义上限、过期证、欠费阈值、并发防超借（Phase 2）

用法（运行器会自动重置种子）：
    python test/recommended_test.py
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import date, timedelta
from urllib.parse import quote

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


def db_exec(sql):
    """执行写/DDL 语句，返回 (returncode, 合并输出)；mysql 不可用返回 (None, '')。"""
    for cmd in ("mysql", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"):
        try:
            r = subprocess.run([cmd, "-uroot", "-p" + DB_PWD, "--default-character-set=utf8mb4",
                                "-e", "USE library_db; " + sql],
                               capture_output=True, text=True, encoding="utf-8",
                               errors="replace", timeout=20)
            return r.returncode, (r.stdout or "") + (r.stderr or "")
        except FileNotFoundError:
            continue
        except Exception as e:
            return -1, str(e)
    return None, ""


def reset_seed():
    """恢复种子（本套件会改额度/借还数据，结尾净零副作用）。"""
    seed = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "docs", "04_数据库设计.sql")
    for cmd in ("mysql", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"):
        try:
            subprocess.run([cmd, "-uroot", "-p" + DB_PWD, "--default-character-set=utf8mb4", "-e",
                            "DROP DATABASE IF EXISTS library_db; CREATE DATABASE library_db "
                            "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"],
                           capture_output=True, timeout=60)
            with open(seed, "rb") as f:
                r = subprocess.run([cmd, "-uroot", "-p" + DB_PWD, "--default-character-set=utf8mb4",
                                    "library_db"], stdin=f, capture_output=True, timeout=120)
            return r.returncode == 0
        except FileNotFoundError:
            continue
        except Exception:
            return False
    return False


def req(method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("token", token)
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


def login(u, p="123456"):
    return req("POST", "/auth/login", body={"username": u, "password": p})[1]


print("=" * 72)
print("LibMS 推荐项回归（热门借阅排行 + 读者额度）")
print("=" * 72)

baseline = {"sys_user": 6, "category": 4, "publisher": 4, "book": 6,
            "borrow_record": 8, "fine_record": 3, "notice": 3,
            "operation_log": 3, "reservation": 0, "notification": 0}
bad = []
for table, want in baseline.items():
    got = db_one(f"SELECT COUNT(*) FROM {table};")
    if got is None:
        print("  [SKIP] mysql 不可用，跳过种子基线校验")
        break
    if got != str(want):
        bad.append(f"{table}={got}(期望{want})")
if bad:
    print(f"  [BASELINE FAIL] 种子基线不符：{' '.join(bad)}")
    print("  请先重置种子（mysql < docs/04_数据库设计.sql）后重跑本脚本")
    sys.exit(1)
else:
    print("  [OK] 种子基线校验通过：10 表 6/4/4/6/8/3/3/3 + 0/0")

at = login("admin")["data"]["token"]
lt = login("librarian01")["data"]["token"]
rt = login("reader001")["data"]["token"]


def ids_of(path):
    c, b = req("GET", path, rt)
    return c, b, [r.get("id") for r in ((b.get("data") or {}).get("records") or [])]


# ============ [1] 热门排序 ============
print("\n[1] 热门排序（种子 borrow_count：3=200, 1=120, 6=90, 2=80, 4=60, 5=45）")
_c, b, ids = ids_of("/books?page=1&size=10&sort=popular")
check("1.1 读者可按热门排序读取", b.get("code") == 200, str(b.get("msg"))[:50])
check("1.2 全量降序 = 3,1,6,2,4,5", ids == [3, 1, 6, 2, 4, 5], f"ids={ids}")

_c, b, ids = ids_of("/books?page=1&size=5&sort=popular")
check("1.3 size=5 返回前 5 本且总数仍为 6",
      len(ids) == 5 and ids == [3, 1, 6, 2, 4] and int((b.get("data") or {}).get("total", 0)) == 6,
      f"ids={ids} total={(b.get('data') or {}).get('total')}")

_c, b, ids = ids_of("/books?page=2&size=2&sort=popular")
check("1.4 分页稳定：第 2 页 size=2 = 6,2", ids == [6, 2], f"ids={ids}")

# 同分按 id 降序：临时建两本 borrow_count=0 的书，值相同按 id 降序
isbn_a = "9780000000911"
isbn_b = "9780000000912"
_c, ba = req("POST", "/books", lt, {"isbn": isbn_a, "title": "热门同分A", "author": "测试",
                                    "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
_c, bb = req("POST", "/books", lt, {"isbn": isbn_b, "title": "热门同分B", "author": "测试",
                                    "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
id_a = db_one(f"SELECT id FROM book WHERE isbn='{isbn_a}';")
id_b = db_one(f"SELECT id FROM book WHERE isbn='{isbn_b}';")
_c, _b, ids_all = ids_of("/books?page=1&size=100&sort=popular")
if id_a and id_b:
    pos_a, pos_b = ids_all.index(int(id_a)), ids_all.index(int(id_b))
    check("1.5 同分按 id 降序（后建的书排前）", pos_b < pos_a, f"posA={pos_a} posB={pos_b} ids={ids_all}")
else:
    check("1.5 同分按 id 降序（后建的书排前）", False, "临时图书创建失败")
for bid in (id_a, id_b):
    if bid:
        req("DELETE", f"/books/{bid}", lt)

# ============ [2] 组合条件 ============
print("\n[2] 组合条件")
_c, b, ids = ids_of("/books?page=1&size=10&sort=popular&keyword=" + quote("Java"))
check("2.1 关键词 + 热门排序（Java → 图书 3）", b.get("code") == 200 and ids == [3], f"ids={ids}")
_c, b, ids = ids_of("/books?page=1&size=10&sort=popular&categoryId=1")
check("2.2 分类 + 热门排序（计算机技术 → 3,1,2）", ids == [3, 1, 2], f"ids={ids}")
_c, b, ids = ids_of("/books?page=1&size=10&sort=popular&onlyStock=true")
check("2.3 只看有库存 + 热门排序（排除 stock=0 的图书 3）",
      ids == [1, 6, 2, 4, 5], f"ids={ids}")

# ============ [3] 权限、边界、默认排序 ============
print("\n[3] 权限与边界")
for name, token in (("管理员", at), ("图书管理员", lt), ("读者", rt)):
    c, b = req("GET", "/books?page=1&size=3&sort=popular", token)
    check(f"3.x {name}可读热门排序", b.get("code") == 200, str(b.get("msg"))[:40])
_c, b = req("GET", "/books?page=1&size=3&sort=DROP", rt)
check("3.1 非法 sort 拒绝（400，不做 SQL 拼接）", b.get("code") == 400, f"code={b.get('code')}")
_c, b, ids = ids_of("/books?page=1&size=10")
check("3.2 默认排序不变（id 降序，首条为 6）", ids and ids[0] == 6, f"ids={ids}")

# 逻辑删除图书不出现在热门榜
_c, bx = req("POST", "/books", lt, {"isbn": "9780000000913", "title": "待删热门图书", "author": "测试",
                                    "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
del_id = db_one("SELECT id FROM book WHERE isbn='9780000000913';")
if del_id:
    req("DELETE", f"/books/{del_id}", lt)
    _c, _b, ids_all = ids_of("/books?page=1&size=100&sort=popular")
    check("3.3 逻辑删除图书不出现在热门榜", int(del_id) not in ids_all, f"deleted={del_id} ids={ids_all}")
else:
    check("3.3 逻辑删除图书不出现在热门榜", False, "临时图书创建失败")

# ============ [4] 读者额度管理（5.3） ============
print("\n[4] 读者额度管理（默认 5 / 自定义上限 / 有效期 / 欠费阈值 / 并发）")

suffix = str(int(time.time()))[-6:]


def find_user(username):
    _c, b = req("GET", "/users?keyword=" + quote(username) + "&size=20", at)
    for r in ((b.get("data") or {}).get("records") or []):
        if r.get("username") == username:
            return r
    return None


def user_body(u, **over):
    body = {
        "id": u["id"], "username": u.get("username"), "realName": u.get("realName"),
        "role": u.get("role"), "readerNo": u.get("readerNo") or "",
        "gender": u.get("gender") if u.get("gender") is not None else 0,
        "phone": u.get("phone") or "", "email": u.get("email") or "",
        "borrowLimit": 5 if u.get("borrowLimit") is None else u.get("borrowLimit"),
        "maxUnpaid": 0 if u.get("maxUnpaid") is None else u.get("maxUnpaid"),
        "validUntil": u.get("validUntil"),
        "emailNotify": 1 if u.get("emailNotify") is None else u.get("emailNotify"),
    }
    body.update(over)
    return body


zs = find_user("reader001")
check("4.1 种子读者默认额度 5 / 最大欠费 0 / 长期有效",
      zs and zs.get("borrowLimit") == 5 and float(zs.get("maxUnpaid") or 0) == 0
      and zs.get("validUntil") is None, str(zs)[:120])

_c, b = req("PUT", f"/users/{zs['id']}", at, user_body(zs, borrowLimit=-1))
check("4.2 borrowLimit=-1 被拒（400）", b.get("code") == 400, str(b.get("msg"))[:40])
_c, b = req("PUT", f"/users/{zs['id']}", at, user_body(zs, borrowLimit=51))
check("4.3 borrowLimit=51 被拒（400）", b.get("code") == 400, str(b.get("msg"))[:40])
_c, b = req("PUT", f"/users/{zs['id']}", at, user_body(zs, maxUnpaid=-1))
check("4.4 maxUnpaid=-1 被拒（400）", b.get("code") == 400, str(b.get("msg"))[:40])
_c, b = req("PUT", f"/users/{zs['id']}", at, user_body(zs, email="not-an-email"))
check("4.5 非法邮箱被拒（400）", b.get("code") == 400, str(b.get("msg"))[:40])

_c, b = req("PUT", f"/users/{zs['id']}", at, user_body(zs, borrowLimit=2))
check("4.6 管理员把张三额度改为 2", b.get("code") == 200, str(b.get("msg"))[:40])
zs = find_user("reader001")
check("4.7 额度修改已生效", zs.get("borrowLimit") == 2, f"limit={zs.get('borrowLimit')}")

_c, b = req("POST", "/borrow", lt, {"userId": zs["id"], "bookId": 2})
check("4.8 在借 2 本、额度 2 时再借被拒",
      b.get("code") != 200 and "上限（2册）" in str(b.get("msg")), str(b.get("msg"))[:50])

_c, b = req("GET", "/borrow-records?userId=3&status=1&size=10", lt)
active = ((b.get("data") or {}).get("records") or [])
_c, b = req("POST", "/return", lt, {"recordId": active[0]["id"]})
check("4.9 还掉一本后额度释放", b.get("code") == 200, str(b.get("msg"))[:40])
_c, b = req("POST", "/borrow", lt, {"userId": zs["id"], "bookId": 2})
check("4.10 还一本后可再借（读者级额度 2 生效）", b.get("code") == 200, str(b.get("msg"))[:50])

# 个人中心不能自助改额度（ProfileDTO 不含该字段，额外字段被忽略）
_c, b = req("PUT", "/profile", rt, {"realName": "张三", "gender": 1, "phone": "13800000003",
                                    "email": "zhangsan@example.com", "emailNotify": 1,
                                    "borrowLimit": 50})
_c, p = req("GET", "/profile", rt)
pdata = p.get("data") or {}
check("4.11 读者无法经 /profile 改额度（仍为 2）",
      b.get("code") == 200 and pdata.get("borrowLimit") == 2, f"limit={pdata.get('borrowLimit')}")
check("4.12 读者可维护本人通知邮箱", pdata.get("email") == "zhangsan@example.com",
      str(pdata.get("email")))

# 借阅证过期：禁借 + 禁预约（仍可登录/还书）
yesterday = (date.today() - timedelta(days=1)).isoformat()
_c, b = req("POST", "/users", at, {"username": "qexp" + suffix, "realName": "过期证", "role": "READER",
                                   "readerNo": "", "gender": 1, "phone": "137" + suffix + "01",
                                   "borrowLimit": 5, "maxUnpaid": 0, "validUntil": yesterday,
                                   "emailNotify": 1})
exp = find_user("qexp" + suffix)
check("4.13 创建过期证读者成功", b.get("code") == 200 and exp is not None, str(b.get("msg"))[:40])
if exp:
    _c, b = req("POST", "/borrow", lt, {"userId": exp["id"], "bookId": 2})
    check("4.14 过期证借书被拒", b.get("code") != 200 and "已过期" in str(b.get("msg")),
          str(b.get("msg"))[:50])
    et = login("qexp" + suffix)["data"]["token"]
    _c, b = req("POST", "/reservations", et, {"bookId": 2})
    check("4.15 过期证预约被拒（与借书同口径）", b.get("code") != 200 and "已过期" in str(b.get("msg")),
          str(b.get("msg"))[:50])

# 欠费阈值：默认 0 沿用"有欠费即禁"，>0 时按金额放行
ww = find_user("reader003")
_c, b = req("PUT", f"/users/{ww['id']}", at, user_body(ww, maxUnpaid=1))
check("4.16 王五最大欠费设为 1 元", b.get("code") == 200, str(b.get("msg"))[:40])
_c, b = req("POST", "/borrow", lt, {"userId": ww["id"], "bookId": 4})
check("4.17 欠费 2.50 超上限 1 时借书被拒",
      b.get("code") != 200 and "超过该读者上限" in str(b.get("msg")), str(b.get("msg"))[:60])
_c, b = req("PUT", f"/users/{ww['id']}", at, user_body(ww, maxUnpaid=5))
check("4.18 上限提到 5 元后允许在限额内借书", b.get("code") == 200, str(b.get("msg"))[:40])
_c, b = req("POST", "/borrow", lt, {"userId": ww["id"], "bookId": 4})
check("4.19 限额内借书成功", b.get("code") == 200, str(b.get("msg"))[:50])
borrow_id = (b.get("data") or {}).get("id")
_c, b = req("PUT", f"/users/{ww['id']}", at, user_body(ww, maxUnpaid=1))
_c, b = req("POST", "/renew", login("reader003")["data"]["token"], {"recordId": borrow_id})
check("4.20 欠费超上限时续借同样被拒", b.get("code") != 200 and "超过该读者上限" in str(b.get("msg")),
      str(b.get("msg"))[:60])
_c, b = req("PUT", f"/users/{ww['id']}", at, user_body(ww, maxUnpaid=5))
_c, b = req("POST", "/renew", login("reader003")["data"]["token"], {"recordId": borrow_id})
check("4.21 限额内续借成功", b.get("code") == 200, str(b.get("msg"))[:50])

# 并发防超借：额度 1 的读者双线程抢最后 1 个名额，只允许 1 单成功
_c, b = req("POST", "/users", at, {"username": "qconc" + suffix, "realName": "并发额度", "role": "READER",
                                   "readerNo": "", "gender": 1, "phone": "136" + suffix + "01",
                                   "borrowLimit": 1, "maxUnpaid": 0, "emailNotify": 1})
cc = find_user("qconc" + suffix)
check("4.22 创建额度 1 的并发测试读者", b.get("code") == 200 and cc is not None, str(b.get("msg"))[:40])
if cc:
    results = {}
    barrier = threading.Barrier(2)

    def worker(book_id):
        barrier.wait()
        results[book_id] = req("POST", "/borrow", lt, {"userId": cc["id"], "bookId": book_id})[1]

    t1 = threading.Thread(target=worker, args=(1,))
    t2 = threading.Thread(target=worker, args=(2,))
    t1.start(); t2.start(); t1.join(); t2.join()
    ok = sum(1 for v in results.values() if v.get("code") == 200)
    check("4.23 双线程抢最后 1 个额度仅 1 单成功", ok == 1, f"ok={ok} detail={results}")
    cc = find_user("qconc" + suffix)
    check("4.24 并发后在借数=1（未突破读者级额度）",
          cc.get("borrowedCount") == 1, f"borrowed={cc.get('borrowedCount')}")

# 数据库级兜底：直接写库的负额度被 CHECK 拒绝
rc, out = db_exec("INSERT INTO sys_user(username,password,real_name,role,borrow_limit) "
                  "VALUES('chk_neg','x','x','READER',-1);")
check("4.25 直接写库负 borrow_limit 被 CHECK 拒绝",
      rc is not None and rc != 0 and "chk_sys_user_borrow_limit" in out,
      f"rc={rc} out={out[:80]}")

# 净零副作用：恢复种子
check("4.99 种子已恢复（净零副作用）", reset_seed(), "")

print("\n" + "=" * 72)
print(f"推荐项测试（热门 + 额度）完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 72)
sys.exit(0 if FAIL == 0 else 1)
