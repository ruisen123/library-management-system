# -*- coding: utf-8 -*-
"""LibMS 预约排队回归（批次 B）

覆盖：
  [1] 预约入口：无副本时排队；有可借副本且无人排队时直接预留（2026-10-22 起现行语义）；
      重复预约/不存在图书被拒
  [2] 排队与权限：队首/队尾位置、读者只能看/取消本人预约、馆员可查全量队列
  [3] 到书与顺延：还书触发队首待取、7 天截止、队尾不得插队、多副本顺序推进
  [4] 借书/续借联动：待取预约优先借阅、预约书禁续借、借走后续借状态置完成
  [5] 回收：过期未取置为已过期并顺延；冻结读者自动取消预约
  [6] 审计：RESERVE / RESERVE_CANCEL / RESERVE_FULFILL / RESERVE_EXPIRE 均留痕

用法（运行器会自动重置种子）：
    python test/reservation_test.py
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


def req(method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("token", token)
    try:
        resp = urllib.request.urlopen(r, timeout=15)
        return resp.status, json.loads(resp.read().decode("utf-8"))
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


def create_reader(at, username, real_name):
    req("POST", "/users", token=at,
        body={"username": username, "realName": real_name, "role": "READER", "gender": 1})
    _c, b = req("GET", "/users?keyword=" + username, token=at)
    return ((b.get("data") or {}).get("records") or [{}])[0].get("id")


print("=" * 72)
print("LibMS 预约排队回归（批次 B）")
print("=" * 72)

# 种子基线（重复执行时提示）
expect = {"sys_user": 6, "category": 4, "publisher": 4, "book": 6,
          "borrow_record": 8, "fine_record": 3, "notice": 3, "operation_log": 3}
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
else:
    print("  [OK] 种子基线校验通过：6/4/4/6/8/3/3/3")

A = login("admin")["data"]["token"]
L = login("librarian01")["data"]["token"]
suffix = str(int(time.time()))[-6:]

u1 = "rva" + suffix
u2 = "rvb" + suffix
u3 = "rvc" + suffix
u4 = "rvd" + suffix
uid1 = create_reader(A, u1, "预约甲")
uid2 = create_reader(A, u2, "预约乙")
uid3 = create_reader(A, u3, "预约丙")
uid4 = create_reader(A, u4, "预约丁")
T2 = login(u2)["data"]["token"]
T3 = login(u3)["data"]["token"]
T4 = login(u4)["data"]["token"]

isbn = "978" + suffix + "1001"
req("POST", "/books", token=L, body={
    "isbn": isbn, "title": "预约测试书", "author": "测试", "categoryId": 1,
    "publisherId": 1, "totalStock": 1, "stock": 1})
book_id = db_one(f"SELECT id FROM book WHERE isbn='{isbn}' AND deleted=0;")
_c, b = req("POST", "/borrow", token=L, body={"userId": uid1, "bookId": int(book_id)})
rec_a1 = (b.get("data") or {}).get("id")
check("0.1 测试书已借出（库存 0，具备预约条件）",
      b.get("code") == 200 and db_one(f"SELECT stock FROM book WHERE id={book_id};") == "0", str(b.get("msg"))[:40])

# ============ [1] 预约入口 ============
print("\n[1] 预约入口")
_c, b = req("POST", "/reservations", token=T2, body={"bookId": int(book_id)})
r_b = b.get("data") or {}
check("1.1 库存 0 时预约成功且为队首", b.get("code") == 200 and r_b.get("queuePosition") == 1,
      f"code={b.get('code')} pos={r_b.get('queuePosition')}")
_c, b = req("POST", "/reservations", token=T3, body={"bookId": int(book_id)})
r_c = b.get("data") or {}
check("1.2 第二位预约排队序号为 2", b.get("code") == 200 and r_c.get("queuePosition") == 2,
      f"pos={r_c.get('queuePosition')}")
_c, b = req("POST", "/reservations", token=T2, body={"bookId": int(book_id)})
check("1.3 重复预约被拒", b.get("code") == 500 and "已预约" in str(b.get("msg")), str(b.get("msg"))[:40])
# 1.4 2026-10-22 起：有可借副本也能预约，且直接进入"待取书"（替读者预留）
#     旧断言「有库存图书无需预约」属旧语义，已作废
_c, b = req("POST", "/reservations", token=T2, body={"bookId": 1})
r_b1 = b.get("data") or {}
check("1.4 有可借副本时预约成功且直接进入待取",
      b.get("code") == 200 and r_b1.get("status") == 2 and r_b1.get("expireTime") is not None,
      f"code={b.get('code')} status={r_b1.get('status')} msg={str(b.get('msg'))[:40]}")
if r_b1.get("id"):
    req("DELETE", "/reservations/" + str(r_b1.get("id")), token=T2)
    # 逻辑删除后记录仍在（status=4），会污染 2.1「乙只有 1 条预约」的计数 → 物理删除复原
    db_one(f"DELETE FROM reservation WHERE id={r_b1.get('id')};")
_c, b = req("POST", "/reservations", token=T2, body={"bookId": 999999})
check("1.5 预约不存在图书 → 404", b.get("code") == 404, str(b.get("msg"))[:40])
_c, b = req("POST", "/reservations", token=T4, body={"bookId": int(book_id), "userId": uid1})
r_d = b.get("data") or {}
check("1.6 请求体注入他人 userId 被忽略（预约仍记在本人丁名下）",
      b.get("code") == 200 and db_one(f"SELECT user_id FROM reservation WHERE id={r_d.get('id')};") == str(uid4),
      f"code={b.get('code')} dbuser={db_one(f'SELECT user_id FROM reservation WHERE id={r_d.get('id')};')}")
req("DELETE", "/reservations/" + str(r_d.get("id")), token=T4)  # 取消，避免影响后续队首顺序

# ============ [2] 权限与队列可见性 ============
print("\n[2] 权限与队列可见性")
_c, b = req("GET", "/reservations/my?page=1&size=10", token=T2)
mine = (b.get("data") or {}).get("records") or []
check("2.1 我的预约仅本人（乙）", b.get("code") == 200 and len(mine) == 1 and mine[0]["userId"] == uid2,
      f"total={(b.get('data') or {}).get('total')}")
_c, b = req("GET", "/reservations/my?page=1&size=10", token=T3)
mine3 = (b.get("data") or {}).get("records") or []
check("2.2 丙的排队位置为第 2 位", len(mine3) == 1 and mine3[0]["queuePosition"] == 2,
      f"pos={mine3[0]['queuePosition'] if mine3 else None}")
_c, b = req("DELETE", "/reservations/" + str(r_b.get("id")), token=T3)
check("2.3 读者取消他人预约 → 403", b.get("code") == 403, str(b.get("msg"))[:40])
_c, b = req("GET", "/reservations?page=1&size=10", token=T3)
check("2.4 读者访问全量预约队列 → 403", b.get("code") == 403, str(b.get("msg"))[:40])
_c, b = req("GET", "/reservations?bookId=" + str(book_id) + "&status=1", token=L)
check("2.5 馆员可按图书查看排队中的预约", b.get("code") == 200 and (b.get("data") or {}).get("total") == 2,
      f"total={(b.get('data') or {}).get('total')}")

# ============ [3] 到书、优先借阅与顺延 ============
print("\n[3] 到书、优先借阅与顺延")
_c, b = req("POST", "/return", token=L, body={"recordId": int(rec_a1)})
check("3.1 还书成功", b.get("code") == 200, str(b.get("msg"))[:40])
_c, b = req("GET", "/reservations/my?status=2", token=T2)
ready = (b.get("data") or {}).get("records") or []
check("3.2 还书后队首（乙）变为待取书，7 天截止",
      len(ready) == 1 and ready[0]["expireTime"] is not None, f"ready={len(ready)}")
_c, b = req("POST", "/borrow", token=L, body={"userId": uid3, "bookId": int(book_id)})
check("3.3 队尾（丙）不得插队借走待取副本",
      b.get("code") == 500 and "前面还有" in str(b.get("msg")), str(b.get("msg"))[:50])
# 3.3b 死锁回归（2026-10-22 修复）：库存=1 但该副本已被乙"待取"占住时，
# 后来的丁预约必须被允许排队；修复前返回"该书当前有可借副本，请直接借阅"，
# 而借书侧又因 stock<=ready 拒绝，形成"约不上也借不到"的死锁（已实测复现）。
_c, b = req("POST", "/reservations", token=T4, body={"bookId": int(book_id)})
r_d2 = b.get("data") or {}
check("3.3b 库存被待取占住时后来者仍可排队（不再约不上也借不到）",
      b.get("code") == 200 and (r_d2.get("queuePosition") or 0) >= 2,
      f"code={b.get('code')} pos={r_d2.get('queuePosition')} msg={str(b.get('msg'))[:40]}")
req("DELETE", "/reservations/" + str(r_d2.get("id")), token=T4)  # 复原，避免影响后续断言

_c, b = req("POST", "/borrow", token=L, body={"userId": uid2, "bookId": int(book_id)})
rec_b = (b.get("data") or {}).get("id")
check("3.4 待取读者（乙）可借走预约图书", b.get("code") == 200, str(b.get("msg"))[:40])
check("3.5 乙的预约已完成",
      db_one(f"SELECT status FROM reservation WHERE id={r_b.get('id')};") == "3")
_c, b = req("POST", "/renew", token=L, body={"recordId": int(rec_b)})
check("3.6 有他人排队时禁止续借", b.get("code") == 500 and "预约" in str(b.get("msg")), str(b.get("msg"))[:40])
_c, b = req("POST", "/return", token=L, body={"recordId": int(rec_b)})
check("3.7 再次还书成功", b.get("code") == 200, str(b.get("msg"))[:40])
_c, b = req("GET", "/reservations/my?status=2", token=T3)
ready3 = (b.get("data") or {}).get("records") or []
check("3.8 副本归还后队首顺延给丙（待取书）", len(ready3) == 1, f"ready={len(ready3)}")

# ============ [4] 过期回收 ============
print("\n[4] 过期回收")
db_one(f"UPDATE reservation SET expire_time=DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id={r_c.get('id')};")
_c, b = req("POST", "/reservations/expire", token=A)
# 2026-10-24（D2-05）：该端点仅系统管理员（ReservationController.java:79-80），
# 原套件只用 admin 调过一次、零负向保护——"仅 ADMIN"这个口径（论文表 3-2 修正依据）无人守门。
# 负向断言并入本条目（等价替换，不新增计数 → 585 不变）。
_c, b_lib = req("POST", "/reservations/expire", token=L)
_c, b_rd = req("POST", "/reservations/expire", token=T3)
check("4.1 管理员手动回收过期预约成功；馆员/读者调用 → 403（该端点仅 ADMIN）",
      b.get("code") == 200 and (b.get("data") or 0) >= 1
      and b_lib.get("code") == 403 and b_rd.get("code") == 403,
      f"admin={b.get('code')} data={b.get('data')} 馆员={b_lib.get('code')} 读者={b_rd.get('code')}")
check("4.2 丙的预约已过期",
      db_one(f"SELECT status FROM reservation WHERE id={r_c.get('id')};") == "5")
_c, b = req("GET", "/reservations/my?status=5", token=T3)
check("4.3 读者可查看已过期预约", b.get("code") == 200 and (b.get("data") or {}).get("total") == 1)

# ============ [5] 取消与冻结联动 ============
print("\n[5] 取消与冻结联动")
_c, b = req("POST", "/borrow", token=L, body={"userId": uid1, "bookId": int(book_id)})
rec_a2 = (b.get("data") or {}).get("id")
_c, b = req("POST", "/reservations", token=T3, body={"bookId": int(book_id)})
r_c2 = b.get("data") or {}
check("5.1 再次借出后可重新预约（序号继续递增）",
      b.get("code") == 200 and r_c2.get("queueNo", 0) >= 3, f"queueNo={r_c2.get('queueNo')}")
_c, b = req("DELETE", "/reservations/" + str(r_c2.get("id")), token=T3)
check("5.2 读者可取消本人预约",
      b.get("code") == 200 and db_one(f"SELECT status FROM reservation WHERE id={r_c2.get('id')};") == "4",
      str(b.get("msg"))[:40])
_c, b = req("POST", "/reservations", token=T2, body={"bookId": int(book_id)})
r_b2 = b.get("data") or {}
_c, b = req("PUT", "/users/" + str(uid2) + "/status", token=A)
check("5.3 冻结读者成功", b.get("code") == 200, str(b.get("msg"))[:40])
check("5.4 冻结后其有效预约自动取消",
      db_one(f"SELECT status FROM reservation WHERE id={r_b2.get('id')};") == "4")
req("PUT", "/users/" + str(uid2) + "/status", token=A)  # 恢复，便于清理
req("POST", "/return", token=L, body={"recordId": int(rec_a2)})

# ============ [7] 并发预约（2026-10-22 修复回归）============
# 修复前 selectMaxQueueNo 走普通快照读，锁住图书行后仍读到旧 max，
# 并发预约算出相同序号撞 uk_reservation_book_queue，实测 8 并发仅 2 成功。
print("\n[7] 并发预约")
import threading

isbn2 = "978" + suffix + "2002"
req("POST", "/books", token=L, body={
    "isbn": isbn2, "title": "并发测试书", "author": "测试", "categoryId": 1,
    "publisherId": 1, "totalStock": 1, "stock": 1})
book2 = int(db_one(f"SELECT id FROM book WHERE isbn='{isbn2}' AND deleted=0;"))
uid5 = create_reader(A, "rve" + suffix, "并发借出")
req("POST", "/borrow", token=L, body={"userId": uid5, "bookId": book2})
conc_ids, conc_tokens = [], []
for i in range(6):
    un = f"rvf{i}{suffix}"
    conc_ids.append(create_reader(A, un, f"并发{i}"))
    conc_tokens.append(login(un)["data"]["token"])

barrier = threading.Barrier(len(conc_tokens))
results = []
rlock = threading.Lock()


def _worker(tok):
    barrier.wait()
    _cc, bb = req("POST", "/reservations", token=tok, body={"bookId": book2})
    with rlock:
        results.append(bb.get("code"))


threads = [threading.Thread(target=_worker, args=(t,)) for t in conc_tokens]
[t.start() for t in threads]
[t.join() for t in threads]
okn = sum(1 for c in results if c == 200)
check("7.1 6 并发预约全部成功（max(queue_no) 已改当前读）", okn == 6, f"成功 {okn}/6 codes={results}")
dist = db_one(f"SELECT COUNT(DISTINCT queue_no) FROM reservation WHERE book_id={book2};")
totq = db_one(f"SELECT COUNT(*) FROM reservation WHERE book_id={book2};")
check("7.2 并发预约排队号无重复", dist == totq, f"distinct={dist} total={totq}")

# 清理并发测试数据（先删子表，再删书与读者）
db_one(f"DELETE FROM reservation WHERE book_id={book2};")
db_one(f"DELETE FROM borrow_record WHERE book_id={book2};")
db_one(f"DELETE FROM book WHERE id={book2};")
for uid in [uid5] + conc_ids:
    db_one(f"DELETE FROM reservation WHERE user_id={uid};")
    db_one(f"DELETE FROM borrow_record WHERE user_id={uid};")
    db_one(f"DELETE FROM sys_user WHERE id={uid};")

# ============ [6] 审计与清理 ============
print("\n[6] 审计与清理")
check("6.1 预约相关审计覆盖（RESERVE/RESERVE_CANCEL/RESERVE_FULFILL/RESERVE_EXPIRE）",
      all(db_one(f"SELECT COUNT(*) FROM operation_log WHERE operation='{op}';") not in (None, "0")
          for op in ("RESERVE", "RESERVE_CANCEL", "RESERVE_FULFILL", "RESERVE_EXPIRE")))
# 清理：预约/借阅流水先删（外键依赖），再删临时书与读者
db_one(f"DELETE FROM reservation WHERE book_id={book_id};")
db_one(f"DELETE FROM borrow_record WHERE book_id={book_id};")
req("DELETE", "/books/" + str(book_id), token=L)
for uid in (uid1, uid2, uid3, uid4):
    req("DELETE", "/users/" + str(uid), token=A)
# 2026-10-24（D2-10）：不再接受 None——查询失败（mysql 不可用/SQL 出错）此前会被判成"清理完毕"
check("6.2 临时数据清理完毕",
      db_one(f"SELECT COUNT(*) FROM reservation WHERE book_id={book_id};") == "0"
      and db_one(f"SELECT COUNT(*) FROM book WHERE isbn='{isbn}' AND deleted=0;") == "0")

print("\n" + "=" * 72)
print(f"预约排队回归完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 72)
sys.exit(0 if FAIL == 0 else 1)
