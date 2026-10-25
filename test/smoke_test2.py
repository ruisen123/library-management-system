# -*- coding: utf-8 -*-
"""
LibMS 后端扩展测试（查漏补缺第二弹）
覆盖上一轮未测的：图书/分类/出版社/用户 CRUD、并发防超借、赔偿登记、
个人中心、逻辑删除后历史记录展示。
基于 docs/05 业务规则。
"""
import json
import os
import subprocess
import sys
import time
import threading
import urllib.request
import urllib.error

BASE = os.environ.get("LIBMS_BASE", "http://localhost:8080")
PASS = 0
FAIL = 0
TMP = {}  # 记录临时创建的数据 id 供清理
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")  # 可用环境变量覆盖（默认本地测试口令，见运行手册）
DB_NAME = os.environ.get("LIBMS_DB_NAME", "library_db")  # 隔离库运行时由环境变量覆盖


def db_one(sql):
    """直接查库返回单值（用于数据一致性对账——API 层返回码无法覆盖账实漂移，如库存回补丢失）。
    mysql 客户端不可用时返回 None（跳过断言，避免无该环境的机器误报失败）。"""
    for cmd in ("mysql", "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"):
        try:
            r = subprocess.run(
                [cmd, "-uroot", "-p" + DB_PWD, "-N", "-e", "USE " + DB_NAME + "; " + sql],
                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15)
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


def req(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("token", token)
    try:
        resp = urllib.request.urlopen(r, timeout=15)
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


def login(u, p="123456"):
    return req("POST", "/auth/login", body={"username": u, "password": p})


print("=" * 70)
print("LibMS 后端扩展测试（CRUD / 并发 / 赔偿 / 个人中心）")
print("=" * 70)
baseline_check()  # M-4：必须在任何请求（含登录）之前——登录会写 LOGIN 日志、破坏 operation_log 基线

admin = login("admin")
libr = login("librarian01")
reader = login("reader001")
at = admin["data"]["token"]
lt = libr["data"]["token"]
rt = reader["data"]["token"]

# ============ 1. 图书 CRUD ============
print("\n[1] 图书 CRUD")
uniq_isbn = "978" + str(int(time.time())).zfill(10)  # 13 位合法 ISBN（适配 @Pattern 校验）
b = req("POST", "/books", token=lt, body={
    "isbn": uniq_isbn, "title": "测试图书A", "author": "测试作者",
    "categoryId": 1, "publisherId": 1, "price": 10.0,
    "totalStock": 3, "stock": 3})
check("新增图书成功", b["code"] == 200, b)
# 新增接口不返回 id，按 ISBN 检索获取
if b["code"] == 200:
    gb = req("GET", f"/books?keyword={uniq_isbn}", token=lt)
    TMP["book"] = gb["data"]["records"][0]["id"] if gb["data"]["records"] else None
else:
    TMP["book"] = None

b2 = req("POST", "/books", token=lt, body={
    "isbn": uniq_isbn, "title": "重复ISBN", "author": "x",
    "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
check("重复 ISBN 被拒绝", b2["code"] != 200, b2["msg"])

# 编辑：可借数 > 总册数 应拒绝
if TMP["book"]:
    b3 = req("PUT", f"/books/{TMP['book']}", token=lt, body={
        "isbn": uniq_isbn, "title": "测试图书A", "author": "测试作者",
        "categoryId": 1, "publisherId": 1, "price": 10.0,
        "totalStock": 2, "stock": 5})
    check("库存自洽校验（可借>总册数拒绝）", b3["code"] != 200, b3["msg"])
    # 合法编辑
    b4 = req("PUT", f"/books/{TMP['book']}", token=lt, body={
        "isbn": uniq_isbn, "title": "测试图书A-改", "author": "测试作者",
        "categoryId": 1, "publisherId": 1, "price": 12.0,
        "totalStock": 3, "stock": 3})
    check("合法编辑图书成功", b4["code"] == 200, b4)

# 删除在借图书应拒绝：找一本当前有在借记录的图书
r = req("GET", "/borrow-records?status=1", token=lt)
if r["code"] == 200 and r["data"]["records"]:
    onloan_book = r["data"]["records"][0]["bookId"]
    d = req("DELETE", f"/books/{onloan_book}", token=lt)
    check("删除在借图书被拒绝", d["code"] != 200, d["msg"])

# 删除无在借的测试图书应成功
if TMP["book"]:
    d2 = req("DELETE", f"/books/{TMP['book']}", token=lt)
    check("删除测试图书成功", d2["code"] == 200, d2)
    # 逻辑删除后检索不到
    g = req("GET", f"/books/{TMP['book']}", token=rt)
    check("逻辑删除后详情不可见（404）", g["code"] == 404, g)

# ============ 2. 分类 CRUD ============
print("\n[2] 分类 CRUD")
uniq_cat = "测试分类%d" % int(time.time())
c = req("POST", "/categories", token=at, body={"name": uniq_cat, "sort": 99})
check("新增分类成功", c["code"] == 200, c)
# 新增接口不返回 id，按 name 检索获取
if c["code"] == 200:
    gc = req("GET", "/categories", token=at)
    TMP["cat"] = next((x["id"] for x in gc["data"] if x["name"] == uniq_cat), None)
else:
    TMP["cat"] = None
c2 = req("POST", "/categories", token=at, body={"name": uniq_cat})
check("重名分类被拒绝", c2["code"] != 200, c2["msg"])
# M-24：动态取"名下确有图书"的分类（借任一图书的 categoryId，不依赖硬编码 id）
gbk = req("GET", "/books?page=1&size=50", token=lt)
cid_busy = gbk["data"]["records"][0]["categoryId"] if gbk.get("data") and gbk["data"].get("records") else 1
c3 = req("DELETE", f"/categories/{cid_busy}", token=at)
check("删除有图书的分类被拒绝", c3["code"] != 200, c3["msg"])
if TMP["cat"]:
    c4 = req("DELETE", f"/categories/{TMP['cat']}", token=at)
    check("删除空分类成功", c4["code"] == 200, c4)

# ============ 3. 出版社 CRUD ============
print("\n[3] 出版社 CRUD")
uniq_pub = "测试出版社%d" % int(time.time())
p = req("POST", "/publishers", token=at, body={"name": uniq_pub})
check("新增出版社成功", p["code"] == 200, p)
# 新增接口不返回 id，按 name 检索获取
if p["code"] == 200:
    gp = req("GET", "/publishers", token=at)
    TMP["pub"] = next((x["id"] for x in gp["data"] if x["name"] == uniq_pub), None)
else:
    TMP["pub"] = None
p2 = req("POST", "/publishers", token=at, body={"name": uniq_pub})
check("重名出版社被拒绝", p2["code"] != 200, p2["msg"])
# M-24：动态取"名下确有图书"的出版社（同上，复用已查询的图书列表）
pid_busy = gbk["data"]["records"][0]["publisherId"] if gbk.get("data") and gbk["data"].get("records") else 1
p3 = req("DELETE", f"/publishers/{pid_busy}", token=at)
check("删除有图书的出版社被拒绝", p3["code"] != 200, p3["msg"])

# ============ 4. 用户 CRUD ============
print("\n[4] 用户 CRUD")
uniq_user = "testr%d" % int(time.time())
u = req("POST", "/users", token=at, body={
    "username": uniq_user, "realName": "测试读者", "role": "READER",
    "readerNo": "R%d" % int(time.time()), "gender": 1, "phone": "13900000000"})
check("新增读者成功", u["code"] == 200, u)
# 新增接口不返回 id，按登录名检索获取
if u["code"] == 200:
    gu = req("GET", f"/users?keyword={uniq_user}", token=at)
    TMP["user"] = next((x["id"] for x in gu["data"]["records"] if x["username"] == uniq_user), None)
else:
    TMP["user"] = None
u2 = req("POST", "/users", token=at, body={"username": uniq_user, "realName": "x", "role": "READER"})
check("重复登录名被拒绝", u2["code"] != 200, u2["msg"])
u3 = req("POST", "/users", token=at, body={"username": "x%d" % int(time.time()), "realName": "x", "role": "INVALID"})
check("非法角色被拒绝", u3["code"] != 200, u3["msg"])
# 新增读者后能用默认密码 123456 登录
if u["code"] == 200:
    ulogin = login(uniq_user)
    check("新读者默认密码可登录", ulogin["code"] == 200, ulogin)
# 重置密码
if TMP["user"]:
    rp = req("PUT", f"/users/{TMP['user']}/password", token=at)
    check("重置密码成功", rp["code"] == 200, rp)
# 冻结/启用切换（查列表确认状态真的翻转，而不是只看业务码）
if TMP["user"]:
    ts = req("PUT", f"/users/{TMP['user']}/status", token=at)
    gs = req("GET", f"/users?keyword={uniq_user}", token=at)
    row_s = next((x for x in (gs.get("data") or {}).get("records", []) if x["username"] == uniq_user), None)
    check("冻结切换成功", ts["code"] == 200 and row_s is not None and row_s.get("status") == 0,
          f"{ts.get('msg')} status={None if row_s is None else row_s.get('status')}")
    ts2 = req("PUT", f"/users/{TMP['user']}/status", token=at)
    gs2 = req("GET", f"/users?keyword={uniq_user}", token=at)
    row_s2 = next((x for x in (gs2.get("data") or {}).get("records", []) if x["username"] == uniq_user), None)
    check("再次切换(启用)成功", ts2["code"] == 200 and row_s2 is not None and row_s2.get("status") == 1,
          f"{ts2.get('msg')} status={None if row_s2 is None else row_s2.get('status')}")
# 删除当前登录账号应拒绝
dself = req("DELETE", "/users/1", token=at)
check("删除当前登录账号被拒绝", dself["code"] != 200, dself["msg"])

# ============ 5. 个人中心 ============
print("\n[5] 个人中心")
prof = req("GET", "/profile", token=rt)
check("查询个人资料", prof["code"] == 200 and prof["data"]["username"] == "reader001", prof)
up = req("PUT", "/profile", token=rt, body={"realName": "张三", "gender": 1, "phone": "13800000003"})
check("修改资料成功", up["code"] == 200, up)
pw_bad = req("PUT", "/profile/password", token=rt, body={"oldPassword": "wrong", "newPassword": "abc123"})
check("原密码错误拒绝改密", pw_bad["code"] != 200, pw_bad["msg"])

# ============ 6. 赔偿登记 ============
print("\n[6] 赔偿登记（库存不恢复）")
# 找一条在借记录，记录其 book 库存，赔偿后库存应不变、borrowed_count-1
r = req("GET", "/borrow-records?status=1", token=lt)
if r["code"] == 200 and r["data"]["records"]:
    rec = r["data"]["records"][0]
    book_before = req("GET", f"/books/{rec['bookId']}", token=lt)["data"]["stock"]
    comp = req("POST", "/fines/compensate", token=lt, body={
        "recordId": rec["id"], "amount": 20.0, "type": 2, "remark": "测试破损"})
    check("赔偿登记成功", comp["code"] == 200, comp)
    if comp["code"] == 200:
        book_after = req("GET", f"/books/{rec['bookId']}", token=lt)["data"]["stock"]
        check("赔偿后库存不恢复", book_after == book_before, f"{book_before}->{book_after}")
        comp2 = req("POST", "/fines/compensate", token=lt, body={
            "recordId": rec["id"], "amount": 20.0, "type": 2})
        check("重复赔偿被拒绝", comp2["code"] != 200, comp2["msg"])

# ============ 7. 并发防超借 ============
print("\n[7] 并发防超借（条件更新）")
# 找一本 stock==1 的书
r = req("GET", "/books?onlyStock=true&size=50", token=lt)
stock1 = [b for b in r["data"]["records"] if b["stock"] == 1]
if stock1:
    bid = stock1[0]["id"]
    # M-23：两个不同读者并发（reader001 id=3 + 本轮新建的临时读者）。
    # 临时读者不可用时直接 SKIP——不退回 reader001 自身（否则退化为同一读者两次请求，测试意图漂移）
    uid2 = TMP["user"]
    if not uid2:
        print("  [SKIP] 临时读者不可用，跳过并发防超借（需先修复上方\"新增读者\"用例）")
    else:
        results = []
        def do_borrow(uid):
            results.append(req("POST", "/borrow", token=lt, body={"userId": uid, "bookId": bid}))
        t1 = threading.Thread(target=do_borrow, args=(3,))
        t2 = threading.Thread(target=do_borrow, args=(uid2,))
        t1.start(); t2.start(); t1.join(); t2.join()
        ok_cnt = sum(1 for x in results if x["code"] == 200)
        book_after = req("GET", f"/books/{bid}", token=lt)["data"]["stock"]
        check("库存1并发借书仅成功1笔", ok_cnt == 1, f"ok={ok_cnt} results={[x['msg'] for x in results]}")
        check("并发后库存=0 不为负", book_after == 0, f"stock={book_after}")
        # 清理：把成功的借阅记录全部还掉（防断言失败时残留脏数据）
        for x in [x for x in results if x["code"] == 200]:
            req("POST", "/return", token=lt, body={"recordId": x["data"]["id"]})
else:
    print("  [SKIP] 无 stock==1 图书，跳过并发防超借")

# ============ 8. 逻辑删除后历史借阅记录展示 ============
print("\n[8] 逻辑删除后历史记录书名展示")
# 找一条已还的历史记录，其 book 若已删除，bookTitle 是否仍能显示
r = req("GET", "/borrow-records?status=2&size=50", token=lt)
if r["code"] == 200:
    hist = r["data"]["records"]
    null_title = [x for x in hist if not x.get("bookTitle")]
    if null_title:
        check("已还记录 bookTitle 不缺失", False, f"{len(null_title)} 条记录 bookTitle 为空（可能是逻辑删除的书）")
    else:
        # 空结果不能算通过：至少要有历史记录，且每条都有书名
        check("已还记录 bookTitle 均能展示", len(hist) > 0 and not null_title,
              f"hist={len(hist)}")

# ============ 9. 清理临时数据 ============
print("\n[9] 清理临时数据")
if TMP.get("user"):
    req("DELETE", f"/users/{TMP['user']}", token=at)
if TMP.get("pub"):
    req("DELETE", f"/publishers/{TMP['pub']}", token=at)

# ============ 10. 数据一致性不变量（DB 层对账）============
# API 层只返回业务码，无法发现"库存回补丢失""冗余计数漂移"这类账实不符（历史 R9-2 教训）；
# 故在关键操作后直连数据库对账，固化为回归断言。
print("\n[10] 数据一致性不变量")
bad = db_one("SELECT COUNT(*) FROM book b WHERE (b.total_stock-b.stock)<>(SELECT COUNT(*) FROM borrow_record br WHERE br.book_id=b.id AND br.status IN(1,3));")
if bad is None:
    print("  [SKIP] 未找到 mysql 客户端，跳过对账断言")
else:
    check("库存自洽 total_stock=stock+在借+已赔偿", bad == "0", f"不一致行数={bad}")

drift = db_one("SELECT COUNT(*) FROM sys_user u WHERE u.role='READER' AND u.borrowed_count<>(SELECT COUNT(*) FROM borrow_record br WHERE br.user_id=u.id AND br.status=1);")
if drift is not None:
    check("读者在借数冗余字段与流水一致", drift == "0", f"漂移行数={drift}")

print("\n" + "=" * 70)
print(f"扩展测试完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 70)
sys.exit(0 if FAIL == 0 else 1)  # M-3：失败时退出码非 0，供自动化/CI 感知
