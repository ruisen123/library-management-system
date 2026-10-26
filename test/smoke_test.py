# -*- coding: utf-8 -*-
"""
LibMS 后端接口冒烟测试（真实运行验证）
覆盖：登录/权限/借书闭环/还书罚金/续借/罚金缴纳/统计/图书检索
基于 docs/05 业务规则：上限5、借期30天、罚金0.5元/天、条件更新防超借
"""
import datetime
import json
import os
import subprocess
import sys
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")  # 可用环境变量覆盖（默认本地测试口令，见运行手册）


def req(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("token", token)
    try:
        resp = urllib.request.urlopen(r, timeout=10)
        return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode("utf-8"))
    except Exception as e:
        return {"code": -1, "msg": "EXC:" + str(e), "data": None}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def db_one(sql):
    """直查数据库返回单值；mysql 不可用返回 None（仅用于种子基线校验，M-4）。"""
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
    """种子基线校验（M-4）：防"上一轮脚本污染未重置"导致的静默缩量/误判。
    mysql 不可用时跳过校验（打印提示，不阻断）。"""
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


def login(u, p="123456"):
    return req("POST", "/auth/login", body={"username": u, "password": p})


print("=" * 70)
print("LibMS 后端接口冒烟测试")
print("=" * 70)
baseline_check()  # M-4：基线不符（未重置种子）时立即退出

# ---------- 1. 登录 ----------
print("\n[1] 登录鉴权")
admin = login("admin")
check("admin 登录成功", admin["code"] == 200 and admin["data"]["token"])
check("admin 密码不回传", "password" not in json.dumps(admin["data"]["user"]))
libr = login("librarian01")
reader = login("reader001")
check("librarian01 登录成功", libr["code"] == 200)
check("reader001 登录成功", reader["code"] == 200)
wrong = login("admin", "badpass")
check("错误密码拒绝", wrong["code"] != 200, wrong["msg"])
frozen = login("reader002")
check("冻结账号拒绝登录", frozen["code"] != 200, frozen["msg"])

at = admin["data"]["token"]
lt = libr["data"]["token"]
rt = reader["data"]["token"]

# ---------- 2. 权限 ----------
print("\n[2] RBAC 权限（401/403）")
r = req("GET", "/users", token=None)
check("无 token 访问 /users 返回 401", r["code"] == 401, r)
r = req("GET", "/users", token=rt)
check("读者访问 /users 返回 403", r["code"] == 403, r)
r = req("GET", "/stats/overview", token=rt)
check("读者访问 /stats 返回 403", r["code"] == 403, r)
r = req("GET", "/stats/overview", token=at)
check("管理员访问 /stats 返回 200", r["code"] == 200, r)

# ---------- 3. 图书检索 ----------
print("\n[3] 图书检索/详情")
r = req("GET", "/books?page=1&size=10", token=rt)
check("读者检索图书 200", r["code"] == 200 and "records" in r["data"], r)
books = r["data"]["records"]
check("返回图书列表非空", len(books) > 0)
if books:
    bid = books[0]["id"]
    r = req("GET", f"/books/{bid}", token=rt)
    check("图书详情 200 含分类/出版社", r["code"] == 200 and r["data"].get("categoryName"), r["data"])
else:
    print("  [SKIP] 图书列表为空，跳过详情用例")
r = req("GET", "/books?onlyStock=true", token=rt)
check("只看有库存（stock>0 且结果非空）",
      r["code"] == 200 and len(r["data"]["records"]) >= 1 and all(b["stock"] > 0 for b in r["data"]["records"]))

# ---------- 4. 借书闭环（借→还） ----------
print("\n[4] 借书/还书闭环")
# 找一本有库存的书 + reader001
r = req("GET", "/books?onlyStock=true&size=5", token=rt)
target = r["data"]["records"][0]
check("找到可借图书", target["stock"] > 0)
before_stock = target["stock"]
before_reader = None
r = req("GET", "/readers?keyword=R20260001", token=lt)
if r["code"] == 200 and r["data"]:
    before_reader = r["data"][0]["borrowedCount"]

borrow = req("POST", "/borrow", token=lt, body={"userId": 3, "bookId": target["id"]})
check("借书成功", borrow["code"] == 200, borrow)
if borrow["code"] == 200:
    rec_id = borrow["data"]["id"]
    # 库存应 -1
    r = req("GET", f"/books/{target['id']}", token=rt)
    check("借书后库存 -1", r["data"]["stock"] == before_stock - 1,
          f"before={before_stock} after={r['data']['stock']}")
    # M-7：在借数冗余字段 +1（借书前已读入 before_reader，原为死代码，此处补回断言）
    r = req("GET", "/readers?keyword=R20260001", token=lt)
    after_cnt = r["data"][0]["borrowedCount"] if r["code"] == 200 and r["data"] else None
    check("借书后在借数 +1（冗余字段随流水更新）",
          before_reader is not None and after_cnt == before_reader + 1,
          f"{before_reader}->{after_cnt}")
    # 还书
    ret = req("POST", "/return", token=lt, body={"recordId": rec_id})
    check("还书成功", ret["code"] == 200, ret)
    r = req("GET", f"/books/{target['id']}", token=rt)
    check("还书后库存恢复", r["data"]["stock"] == before_stock,
          f"before={before_stock} after={r['data']['stock']}")
    # 重复还书应失败
    ret2 = req("POST", "/return", token=lt, body={"recordId": rec_id})
    check("重复还书被拒绝", ret2["code"] != 200, ret2["msg"])
else:
    print("  [SKIP] 借书未成功，跳过库存/还书后续用例")

# 借书边界：库存为0（book id=3）
print("\n[4b] 借书边界（库存不足）")
r = req("GET", "/books/3", token=rt)
check("图书3库存为0", r["code"] == 200 and r["data"]["stock"] == 0)
b = req("POST", "/borrow", token=lt, body={"userId": 3, "bookId": 3})
check("无库存借书被拒绝", b["code"] != 200, b["msg"])

# 借书边界：冻结读者（reader002 id=4）
print("\n[4c] 借书边界（冻结读者）")
b = req("POST", "/borrow", token=lt, body={"userId": 4, "bookId": target["id"]})
check("冻结读者借书被拒绝", b["code"] != 200, b["msg"])

# 借书边界：欠费读者（reader003 id=5，有未缴罚金）
print("\n[4d] 借书边界（欠费读者）")
b = req("POST", "/borrow", token=lt, body={"userId": 5, "bookId": target["id"]})
check("欠费读者借书被拒绝", b["code"] != 200, b["msg"])

# ---------- 5. 续借 ----------
print("\n[5] 续借")
r = req("GET", "/my-borrows?status=1", token=rt)
my = r["data"]["records"]
check("读者在借列表", r["code"] == 200)
today = datetime.date.today().isoformat()  # M-1（第十九轮补实）：动态"未逾期"过滤，与种子相对日期解耦
renewable = [m for m in my if m["renewCount"] == 0 and m["dueDate"] >= today]
if renewable:
    rec = renewable[0]
    rn = req("POST", "/renew", token=rt, body={"recordId": rec["id"]})
    check("续借成功", rn["code"] == 200, rn)
    if rn["code"] == 200:
        expected_due = (datetime.date.fromisoformat(rec["dueDate"]) + datetime.timedelta(days=30)).isoformat()
        check("续借后 dueDate +30 天", rn["data"]["dueDate"] == expected_due,
              f"{rec['dueDate']}->{rn['data']['dueDate']}（期望 {expected_due}）")
        rn2 = req("POST", "/renew", token=rt, body={"recordId": rec["id"]})
        check("二次续借被拒绝", rn2["code"] != 200, rn2["msg"])
else:
    print("  [SKIP] 无可续借记录（跳过续借用例）")

# 续借越权：读者续借他人记录
print("\n[5b] 续借越权防护")
# reader001 尝试续借 reader004(user_id=6) 的在借记录 id=5
rn = req("POST", "/renew", token=rt, body={"recordId": 5})
check("读者续借他人记录返回 403", rn["code"] == 403, rn)

# ---------- 6. 逾期还书罚金 ----------
print("\n[6] 逾期还书罚金")
# reader004(id=6) 有逾期记录 id=5（种子构造为恒逾期：due = 导入日 − 24 天，随种子重置始终逾期）
r = req("GET", "/readers?keyword=reader004", token=lt)
check("检索 reader004", r["code"] == 200)
r = req("GET", "/borrow-records?userId=6&status=1", token=lt)
overdue_rec = [x for x in r["data"]["records"] if x["id"] == 5]
check("reader004 存在逾期在借记录", len(overdue_rec) > 0)
if overdue_rec:
    ret = req("POST", "/return", token=lt, body={"recordId": 5})
    check("逾期还书成功", ret["code"] == 200, ret)
    if ret["code"] == 200:
        days = ret["data"].get("overdueDays", 0)
        amt = ret["data"].get("fineAmount")
        check("罚金金额=逾期天数×0.5", amt is not None and float(amt) == days * 0.5,
              f"days={days} amt={amt}")
        check("罚金金额>0", float(amt) > 0, str(amt))

# ---------- 7. 罚金缴纳 ----------
print("\n[7] 罚金查询/缴纳")
r = req("GET", "/fines?status=1", token=lt)
check("罚金列表 200", r["code"] == 200)
unpaid = r["data"]["records"]
if unpaid:
    fid = unpaid[0]["id"]
    pay = req("PUT", f"/fines/{fid}/pay", token=lt)
    check("罚金标记已缴", pay["code"] == 200, pay)
    pay2 = req("PUT", f"/fines/{fid}/pay", token=lt)
    check("重复缴纳被拒绝", pay2["code"] != 200, pay2["msg"])
else:
    print("  [SKIP] 无未缴罚金，跳过缴纳用例")
# 读者只能看自己罚金
r = req("GET", "/fines", token=rt)
fine_records = (r.get("data") or {}).get("records") or []
# 种子 reader001 没有罚金记录；若接口忽略读者过滤返回他人罚金，这里会立刻变红
check("读者查本人罚金 200 且仅本人（种子该读者为空）",
      r["code"] == 200 and len(fine_records) == 0,
      f"count={len(fine_records)} uids={set(f.get('userId') for f in fine_records)}")

# ---------- 8. 统计 ----------
print("\n[8] 统计接口")
for p, key in [("/stats/overview", "bookKinds"), ("/stats/trend", "months"),
               ("/stats/pie", None), ("/stats/top", None)]:
    r = req("GET", p, token=at)
    check(f"{p} 返回 200", r["code"] == 200, r)
r = req("GET", "/stats/top", token=at)
check("TOP10 返回≤10条且非空", 0 < len(r["data"]) <= 10, f"len={len(r['data'])}")

# ---------- 9. 越权写操作 ----------
print("\n[9] 越权写操作防护")
r = req("POST", "/books", token=rt, body={"isbn": "x", "title": "x"})
check("读者新增图书返回 403", r["code"] == 403, r)
r = req("POST", "/borrow", token=rt, body={"userId": 3, "bookId": 1})
check("读者调用借书接口返回 403", r["code"] == 403, r)

# ---------- 10. 基础端点探活（M-13：消除单脚本覆盖，无副作用用例）----------
print("\n[10] 基础端点探活（出版社/公告/规则/日志/改密拒绝路径）")
r = req("GET", "/publishers", token=lt)
check("出版社列表 200", r["code"] == 200 and len(r["data"]) > 0, r)
r = req("GET", "/notices?page=1&size=5", token=rt)
check("公告列表 200（读者可见）", r["code"] == 200, r)
r = req("GET", "/rule/config", token=at)
check("借阅规则 200（admin，maxBorrow=5）", r["code"] == 200 and r["data"]["maxBorrow"] == 5, r)
r = req("GET", "/logs?page=1&size=5", token=at)
check("操作日志查询 200（admin）", r["code"] == 200, r)
r = req("PUT", "/profile/password", token=rt, body={"oldPassword": "wrong-old", "newPassword": "x123456"})
check("改密-原密码错误被拒（无副作用探活）", r["code"] != 200, r["msg"])

# ---------- 11. 业务规则补充（第二十二轮：累计计数递增 / 冻结读者还书）----------
print("\n[11] 业务规则补充（borrow_count 递增 / 冻结读者可还书）")
r = req("GET", "/books?onlyStock=true&page=1&size=1", token=lt)
bid = r["data"]["records"][0]["id"] if r["code"] == 200 and r["data"]["records"] else None
b1 = req("GET", f"/books/{bid}", token=lt)["data"]["borrowCount"] if bid else None
r = req("POST", "/borrow", token=lt, body={"userId": 3, "bookId": bid})
check("11.1 借书成功（补充场景）", r["code"] == 200, r)
b2 = req("GET", f"/books/{bid}", token=lt)["data"]["borrowCount"] if bid else None
check("11.2 累计借阅数 borrow_count 随借书 +1", b1 is not None and b2 == b1 + 1, f"{b1} -> {b2}")
rid = r["data"]["id"] if isinstance(r.get("data"), dict) else None
r2 = req("POST", "/return", token=lt, body={"recordId": rid})
b3 = req("GET", f"/books/{bid}", token=lt)["data"]["borrowCount"] if bid else None
check("11.3 还书后累计借阅数不变（只增不减语义）", r2["code"] == 200 and b3 == b2, f"{b2} -> {b3}")
# 冻结读者还书（reader002 李四的在借记录 8）——放最后执行，其状态变化不影响前序断言
r = req("POST", "/return", token=lt, body={"recordId": 8})
check("11.4 冻结读者可还书（冻结可还不可借）", r["code"] == 200, r.get("msg"))

# ---------- 汇总 ----------
print("\n" + "=" * 70)
print(f"测试完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 70)
sys.exit(0 if FAIL == 0 else 1)  # M-3：失败时退出码非 0，供自动化/CI 感知
