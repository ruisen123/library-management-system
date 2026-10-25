# -*- coding: utf-8 -*-
"""
LibMS 并发防超借专项测试（docs/05 难点1）
验证：条件更新 AND stock>0 在并发下不超借。
场景：stock=1 的书，两个正常读者并发借，仅 1 笔成功、库存=0 不为负。
"""
import json
import sys
import time
import threading
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0


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


print("=" * 70)
print("LibMS 并发防超借专项测试")
print("=" * 70)

libr = req("POST", "/auth/login", body={"username": "librarian01", "password": "123456"})
admin = req("POST", "/auth/login", body={"username": "admin", "password": "123456"})
lt = libr["data"]["token"]
at = admin["data"]["token"]

# 1. 建 2 个临时正常读者
ts = int(time.time())
rids = []
for i in range(2):
    u = req("POST", "/users", token=at, body={
        "username": f"conc{i}_{ts}", "realName": f"并发读者{i}", "role": "READER",
        "readerNo": f"C{i}{ts}", "gender": 1})
    if u["code"] == 200:
        gu = req("GET", f"/users?keyword=conc{i}_{ts}", token=at)
        if gu["data"]["records"]:
            rids.append(gu["data"]["records"][0]["id"])
check("创建 2 个临时读者", len(rids) == 2, rids)

# 2. 建 1 本 stock=1 的临时书
isbn = "978" + str(int(ts)).zfill(10)  # 13 位合法 ISBN（适配 @Pattern 校验）
b = req("POST", "/books", token=lt, body={
    "isbn": isbn, "title": "并发测试书", "author": "x",
    "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
check("创建 stock=1 临时书", b["code"] == 200, b)
gb = req("GET", f"/books?keyword={isbn}", token=lt)
bid = gb["data"]["records"][0]["id"] if gb["data"]["records"] else None
check("检索到临时书 id", bid is not None)

if len(rids) == 2 and bid:
    # 3. 并发借书：两个读者同时借 stock=1 的书
    results = []
    barrier = threading.Barrier(2)

    def do_borrow(uid):
        barrier.wait()
        results.append(req("POST", "/borrow", token=lt, body={"userId": uid, "bookId": bid}))

    t1 = threading.Thread(target=do_borrow, args=(rids[0],))
    t2 = threading.Thread(target=do_borrow, args=(rids[1],))
    t1.start(); t2.start(); t1.join(); t2.join()

    ok_cnt = sum(1 for x in results if x["code"] == 200)
    stock_after = req("GET", f"/books/{bid}", token=lt)["data"]["stock"]
    check("stock=1 并发借书仅 1 笔成功", ok_cnt == 1, f"ok={ok_cnt} msgs={[x['msg'] for x in results]}")
    check("并发后库存=0 不为负", stock_after == 0, f"stock={stock_after}")

    # 4. 清理：全量归还成功记录（超借发生时不止一笔，M-9：防清理依赖断言成立）、删临时书、删临时读者
    succ = [x for x in results if x["code"] == 200]
    for x in succ:
        req("POST", "/return", token=lt, body={"recordId": x["data"]["id"]})
    req("DELETE", f"/books/{bid}", token=lt)
    for rid in rids:
        req("DELETE", f"/users/{rid}", token=at)
    print("  [INFO] 临时数据已清理")

print("\n" + "=" * 70)
print(f"并发测试完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 70)
sys.exit(0 if FAIL == 0 else 1)  # M-3：失败时退出码非 0，供自动化/CI 感知
