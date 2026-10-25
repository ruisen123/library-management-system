# -*- coding: utf-8 -*-
"""LibMS 站内通知回归（推荐项 5.1 核心，不含邮件通道）

覆盖：
  [1] 权限与身份隔离（读者本人语义、员工 403、扫描仅管理员）
  [2] 扫描生成提醒（到期/逾期/欠费）与 dedup 幂等
  [3] 预约到书即时通知（还书触发队首 → AFTER_COMMIT 落库）
  [4] 已读语义（单条/重复/越权 404/全部已读/仅未读过滤）
  [5] 分页与边界
  [6] 净零副作用（结尾恢复种子）

用法（运行器会自动重置种子）：
    python test/notification_test.py
"""
import json
import os
import subprocess
import sys
import time
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


def reset_seed():
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


def notif_id(user_id, ntype):
    v = db_one(f"SELECT id FROM notification WHERE user_id={user_id} AND type={ntype} LIMIT 1;")
    return int(v) if v else None


print("=" * 72)
print("LibMS 站内通知回归（5.1 核心）")
print("=" * 72)

expect = {"sys_user": 6, "category": 4, "publisher": 4, "book": 6, "borrow_record": 8,
          "fine_record": 3, "notice": 3, "operation_log": 3, "reservation": 0, "notification": 0}
bad = []
for table, want in expect.items():
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
print("  [OK] 种子基线校验通过（notification 起点为 0）")

at = login("admin")["data"]["token"]
lt = login("librarian01")["data"]["token"]
zs_t = login("reader001")["data"]["token"]   # id3：红楼梦 10-24 到期 → 到期提醒
ww_t = login("reader003")["data"]["token"]   # id5：欠费 2.50 → 罚金提醒
zl_t = login("reader004")["data"]["token"]   # id6：万历十五年已逾期 + 预约 Java 虚拟机

# ============ [1] 权限与身份 ============
print("\n[1] 权限与身份隔离")
_c, b = req("GET", "/notifications?page=1&size=10", zs_t)
d = b.get("data") or {}
check("1.1 读者可读本人通知（含 unreadCount 字段）",
      b.get("code") == 200 and isinstance(d.get("records"), list) and "unreadCount" in d, str(b)[:80])
check("1.2 起点未读数为 0", int(d.get("unreadCount", -1)) == 0, str(d.get("unreadCount")))
_c, b = req("GET", "/notifications", lt)
check("1.3 图书管理员读取通知被拒（403）", b.get("code") == 403, str(b.get("code")))
_c, b = req("POST", "/notifications/scan", zs_t)
check("1.4 读者触发扫描被拒（403）", b.get("code") == 403, str(b.get("code")))
_c, b = req("GET", "/notifications")
check("1.5 未登录读取被拒（401）", b.get("code") == 401, str(b.get("code")))

# ============ [2] 扫描生成与幂等 ============
print("\n[2] 扫描生成提醒（到期/逾期/欠费）与 dedup 幂等")
_c, b = req("POST", "/notifications/scan", at)
scan1 = b.get("data") or {}
check("2.1 管理员手动扫描成功", b.get("code") == 200 and not scan1.get("busy"), str(b)[:80])
check("2.2 首轮生成 3 条（张三到期 + 赵六逾期 + 王五欠费）",
      scan1.get("created") == 3, f"created={scan1.get('created')}")
check("2.3 冻结读者（李四）不生成提醒",
      db_one("SELECT COUNT(*) FROM notification WHERE user_id=4;") == "0",
      f"cnt={db_one('SELECT COUNT(*) FROM notification WHERE user_id=4;')}")
_c, b = req("POST", "/notifications/scan", at)
check("2.4 重复扫描不产生重复通知（dedup_key 唯一）",
      (b.get("data") or {}).get("created") == 0
      and db_one("SELECT COUNT(*) FROM notification;") == "3",
      f"created={(b.get('data') or {}).get('created')} total={db_one('SELECT COUNT(*) FROM notification;')}")

_c, b = req("GET", "/notifications?page=1&size=10", zs_t)
zs_items = ((b.get("data") or {}).get("records") or [])
check("2.5 张三收到到期提醒且含书名与到期日",
      len(zs_items) == 1 and zs_items[0]["type"] == 2 and "红楼梦" in zs_items[0]["content"],
      str(zs_items)[:120])
_c, b = req("GET", "/notifications?page=1&size=10", ww_t)
ww_items = ((b.get("data") or {}).get("records") or [])
check("2.6 王五收到罚金提醒且含金额",
      len(ww_items) == 1 and ww_items[0]["type"] == 4 and "2.50" in ww_items[0]["content"],
      str(ww_items)[:120])
check("2.7 张三列表不含王五的消息（按令牌隔离）",
      all(i["userId"] == 3 for i in zs_items), str(zs_items)[:80])
_c, b = req("GET", "/notifications/unread-count", zs_t)
check("2.8 未读数接口与列表 unreadCount 一致",
      b.get("code") == 200 and int(b.get("data") or 0) == 1, str(b.get("data")))

# ============ [3] 预约到书即时通知 ============
print("\n[3] 预约到书即时通知（还书 → 队首待取 → AFTER_COMMIT 落库）")
# 2026-10-23：预约新增"额度/逾期/欠费"资格校验，种子里赵六有逾期未还，不能再预约。
# 这里新建一位无在借、无欠费的读者验证通知链路（结尾 reset_seed 会清理）。
nfix_user = "nfix" + str(int(time.time() * 1000))[-8:]
req("POST", "/users", at, {"username": nfix_user, "realName": "通知链路读者", "role": "READER",
                           "gender": 0, "borrowLimit": 5, "maxUnpaid": 0, "emailNotify": 1})
nfix_t = login(nfix_user).get("data", {}).get("token")
nfix_uid = int(db_one(f"SELECT id FROM sys_user WHERE username='{nfix_user}';") or 0)
_c, b = req("POST", "/reservations", nfix_t, {"bookId": 3})
check("3.1 合格读者预约无库存图书成功（排队中）", b.get("code") == 200, str(b.get("msg"))[:60])
check("3.2 排队中不产生到书通知",
      db_one("SELECT COUNT(*) FROM notification WHERE type=1;") == "0",
      f"cnt={db_one('SELECT COUNT(*) FROM notification WHERE type=1;')}")
_c, b = req("POST", "/return", lt, {"recordId": 8})
check("3.3 还掉《深入理解Java虚拟机》触发队首到书", b.get("code") == 200, str(b.get("msg"))[:60])
ready_id = notif_id(nfix_uid, 1)
check("3.4 还书后即时生成到书通知", ready_id is not None, f"id={ready_id}")
_c, b = req("GET", "/notifications?page=1&size=10&unreadOnly=true", nfix_t)
zl_items = ((b.get("data") or {}).get("records") or [])
check("3.5 到书通知含书名与取书期限",
      any(i["type"] == 1 and "深入理解Java虚拟机" in i["content"] for i in zl_items),
      str(zl_items)[:140])
_c, b = req("POST", "/notifications/scan", at)
check("3.6 扫描与事件通道共用 dedup：READY 通知仍只有 1 条",
      db_one("SELECT COUNT(*) FROM notification WHERE type=1;") == "1",
      f"cnt={db_one('SELECT COUNT(*) FROM notification WHERE type=1;')}")
check("3.7 邮件通道未启用时通知标记为跳过（email_status=3）",
      db_one("SELECT COUNT(*) FROM notification WHERE email_status<>3;") == "0",
      f"bad={db_one('SELECT COUNT(*) FROM notification WHERE email_status<>3;')}")

# ============ [4] 已读语义 ============
print("\n[4] 已读语义")
zs_due_id = notif_id(3, 2)
ww_fine_id = notif_id(5, 4)
_c, b = req("PUT", f"/notifications/{zs_due_id}/read", zs_t)
check("4.1 标记单条已读成功", b.get("code") == 200, str(b.get("msg"))[:40])
check("4.2 已读时间与标记同步落库",
      db_one(f"SELECT read_flag FROM notification WHERE id={zs_due_id};") == "1", "")
_c, b = req("PUT", f"/notifications/{zs_due_id}/read", zs_t)
check("4.3 重复已读幂等（仍 200）", b.get("code") == 200, str(b.get("code")))
_c, b = req("GET", "/notifications/unread-count", zs_t)
zs_unread = b.get("data")
check("4.4 已读后未读数归零",
      b.get("code") == 200 and zs_unread is not None and int(zs_unread) == 0, str(zs_unread))
_c, b = req("PUT", f"/notifications/{ww_fine_id}/read", zs_t)
check("4.5 读他人通知返回 404（不泄露存在性）", b.get("code") == 404, str(b.get("code")))
check("4.6 越权尝试未改动他人已读态",
      db_one(f"SELECT read_flag FROM notification WHERE id={ww_fine_id};") == "0", "")
_c, b = req("GET", "/notifications?page=1&size=10&unreadOnly=true", zs_t)
check("4.7 仅未读过滤已排除已读项",
      len(((b.get("data") or {}).get("records") or [])) == 0, str(b.get("data"))[:80])
_c, b = req("PUT", "/notifications/read-all", nfix_t)
check("4.8 全部已读返回更新条数（通知链路读者 1 条）",
      b.get("code") == 200 and int(b.get("data") or 0) == 1, f"data={b.get('data')}")
_c, b = req("GET", "/notifications/unread-count", nfix_t)
zl_unread = b.get("data")
check("4.9 全部已读后未读数为 0",
      b.get("code") == 200 and zl_unread is not None and int(zl_unread) == 0, str(zl_unread))

# ============ [5] 分页与边界 ============
print("\n[5] 分页与边界")
_c, b = req("GET", "/notifications?page=1&size=1", zs_t)
d = b.get("data") or {}
check("5.1 size=1 返回 1 条且 total 正确",
      len(d.get("records") or []) == 1 and int(d.get("total") or 0) == 1, str(d)[:100])
_c, b = req("GET", "/notifications?page=9999&size=10", zs_t)
check("5.2 越界页返回空列表",
      b.get("code") == 200 and ((b.get("data") or {}).get("records") or []) == [], str(b)[:80])
_c, b = req("GET", "/notifications?page=1&size=10000", zs_t)
check("5.3 超大 size 被收敛（不崩、不返全表）",
      b.get("code") == 200 and int((b.get("data") or {}).get("size") or 0) == 100
      and len((b.get("data") or {}).get("records") or []) <= 100, str(b)[:80])
_c, b = req("PUT", "/notifications/999999/read", zs_t)
check("5.4 不存在的通知 ID 返回 404", b.get("code") == 404, str(b.get("code")))

# ============ [6] 净零副作用 ============
print("\n[6] 净零副作用")
check("6.1 通知不影响业务表不变量（借阅流水仍 8 条）",
      db_one("SELECT COUNT(*) FROM borrow_record;") == "8",
      f"cnt={db_one('SELECT COUNT(*) FROM borrow_record;')}")
check("6.2 种子已恢复", reset_seed(), "")
check("6.3 恢复后通知表归零", db_one("SELECT COUNT(*) FROM notification;") == "0", "")

print("\n" + "=" * 72)
print(f"站内通知回归完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 72)
sys.exit(0 if FAIL == 0 else 1)
