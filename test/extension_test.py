# -*- coding: utf-8 -*-
"""LibMS 扩展功能回归（批次 A）：读者自助注册 + 续借欠费联动

覆盖：
  [1] 自助注册：注册即登录、角色固定 READER、证号系统分配、密码 BCrypt、审计留痕
  [2] 注册边界：重复登录名 / 重复手机号 / 弱密码 / 非法手机号 / 非法登录名 / role 提权注入
  [3] 续借联动：有未缴罚金禁续借，缴清后可续借
  [4] 注册限流：同一 IP 窗口内超过阈值被拒（本段会占用限流额度，故放在最后）

用法（运行器会自动重置种子）：
    python test/extension_test.py
注意：限流计数保存在后端进程内存；重复执行本套件前需重启后端，否则第一段注册会被上一轮限流拦截。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import re

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


print("=" * 72)
print("LibMS 扩展功能回归（批次 A：自助注册 / 续借欠费联动）")
print("=" * 72)

# 本套件不直接改种子数据；先确认种子基线（重复执行时提示而不是静默漂移）
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

at = login("admin")["data"]["token"]
lt = login("librarian01")["data"]["token"]
rt = login("reader001")["data"]["token"]

suffix = str(int(time.time()))[-6:]
reg_user = "reg" + suffix
reg_phone = "139" + suffix + "01"
created_users = []

# ============ [1] 自助注册成功路径 ============
print("\n[1] 自助注册（成功路径）")
_c, b = req("POST", "/auth/register", body={
    "username": reg_user, "password": "abc12345", "realName": "自助读者",
    "phone": reg_phone, "gender": 1
})
data = b.get("data") or {}
user = data.get("user") or {}
check("1.1 注册成功并返回令牌（注册即登录）",
      b.get("code") == 200 and bool(data.get("token")), str(b.get("msg"))[:50])
if b.get("code") == 200:
    created_users.append(user.get("id"))
    _c, p = req("GET", "/profile", token=data["token"])
    check("1.2 注册返回的令牌可访问个人中心", p.get("code") == 200 and (p.get("data") or {}).get("username") == reg_user)
check("1.3 注册角色固定为 READER（公开接口不可自选角色）",
      user.get("role") == "READER", f"role={user.get('role')}")
reader_no = user.get("readerNo") or ""
check("1.4 系统自动分配读者证号（R+年份+6位序号）",
      bool(re.fullmatch(r"R\d{4}\d{6}", reader_no)), f"readerNo={reader_no}")
pwd_db = db_one(f"SELECT password FROM sys_user WHERE username='{reg_user}' AND deleted=0;")
check("1.5 密码以 BCrypt 密文入库（非明文）",
      pwd_db is not None and pwd_db.startswith("$2"), str(pwd_db)[:12])
check("1.6 注册写入审计（REGISTER）",
      db_one(f"SELECT COUNT(*) FROM operation_log WHERE operation='REGISTER' AND user_id={user.get('id')};") == "1")
_c, b = req("POST", "/auth/login", body={"username": reg_user, "password": "abc12345"})
check("1.7 注册后可用新口令登录", b.get("code") == 200, str(b.get("msg"))[:40])

# ============ [2] 注册边界 ============
print("\n[2] 注册边界与提权防护")
_c, b = req("POST", "/auth/register", body={
    "username": reg_user, "password": "abc12345", "realName": "重复", "phone": "13900000001"
})
check("2.1 重复登录名被拒", b.get("code") == 500 and "登录名" in str(b.get("msg")), str(b.get("msg"))[:40])
_c, b = req("POST", "/auth/register", body={
    "username": "regdup" + suffix, "password": "abc12345", "realName": "重复手机", "phone": reg_phone
})
check("2.2 重复手机号被拒", b.get("code") == 500 and "手机号" in str(b.get("msg")), str(b.get("msg"))[:40])
_c, b = req("POST", "/auth/register", body={
    "username": "regweak" + suffix, "password": "abcdefgh", "realName": "弱密码", "phone": "139" + suffix + "02"
})
check("2.3 纯字母弱密码被拒（400）", b.get("code") == 400, str(b.get("msg"))[:40])
_c, b = req("POST", "/auth/register", body={
    "username": "regphone" + suffix, "password": "abc12345", "realName": "坏手机", "phone": "12345"
})
check("2.4 非法手机号被拒（400）", b.get("code") == 400, str(b.get("msg"))[:40])
_c, b = req("POST", "/auth/register", body={
    "username": "1bad" + suffix, "password": "abc12345", "realName": "坏登录名", "phone": "139" + suffix + "03"
})
check("2.5 非法登录名被拒（400）", b.get("code") == 400, str(b.get("msg"))[:40])
# role 提权注入：DTO 不接收 role，Jackson 忽略未知字段，落库仍应为 READER
_c, b = req("POST", "/auth/register", body={
    "username": "regrole" + suffix, "password": "abc12345", "realName": "提权测试",
    "phone": "139" + suffix + "04", "role": "ADMIN"
})
inject_id = ((b.get("data") or {}).get("user") or {}).get("id")
if inject_id:
    created_users.append(inject_id)
check("2.6 请求体注入 role=ADMIN 被忽略（落库仍 READER）",
      b.get("code") == 200 and db_one(f"SELECT role FROM sys_user WHERE id={inject_id};") == "READER",
      f"code={b.get('code')} dbrole={db_one(f'SELECT role FROM sys_user WHERE id={inject_id};')}")

# ============ [3] 续借欠费联动 ============
print("\n[3] 续借欠费联动（临时读者/图书/罚金，测后清理）")
book_body = {"isbn": "978" + suffix + "0001", "title": "续借联动测试书", "author": "测试",
             "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1}
_c, b = req("POST", "/books", token=lt, body=book_body)
book_id = db_one(f"SELECT id FROM book WHERE isbn='{book_body['isbn']}' AND deleted=0;")
temp_uid = user.get("id") if user else None
_c, b = req("POST", "/borrow", token=lt, body={"userId": temp_uid, "bookId": int(book_id)})
record_id = (b.get("data") or {}).get("id") if b.get("code") == 200 else None
check("3.1 注册读者可立即借书（额度/状态正常）", b.get("code") == 200 and record_id is not None, str(b.get("msg"))[:40])
if record_id:
    # 直接造一笔未缴罚金（模拟馆员线下已登记但读者未缴）
    db_one(f"INSERT INTO fine_record(user_id, borrow_id, amount, type, status, remark) "
           f"VALUES({temp_uid}, {record_id}, 0.50, 1, 1, '续借联动测试');")
    _c, b = req("POST", "/renew", token=lt, body={"recordId": int(record_id)})
    check("3.2 有未缴罚金时续借被拒", b.get("code") == 500 and "罚金" in str(b.get("msg")), str(b.get("msg"))[:40])
    fine_id = db_one(f"SELECT id FROM fine_record WHERE borrow_id={record_id} AND status=1;")
    _c, b = req("PUT", f"/fines/{fine_id}/pay", token=lt)
    check("3.3 缴清罚金", b.get("code") == 200, str(b.get("msg"))[:40])
    _c, b = req("POST", "/renew", token=lt, body={"recordId": int(record_id)})
    check("3.4 缴清后同一记录可续借", b.get("code") == 200, str(b.get("msg"))[:40])
    req("POST", "/return", token=lt, body={"recordId": int(record_id)})
    # 清理：先删罚金（已缴）与借阅流水，再删书/读者，避免外键与护栏阻挡
    db_one(f"DELETE FROM fine_record WHERE borrow_id={record_id};")
    db_one(f"DELETE FROM borrow_record WHERE id={record_id};")
    req("DELETE", f"/books/{book_id}", token=lt)
    req("DELETE", f"/users/{temp_uid}", token=at)
    created_users.remove(temp_uid)

# ============ [4] 注册限流（放最后：会占满本 IP 窗口额度） ============
print("\n[4] 注册限流（同 IP 滑动窗口）")
# 前面已消耗 3 次额度（成功 1 + 重复登录名 1 + 重复手机号 1 + role 注入 1 = 4），再补足到阈值
for i in range(4):
    uname = f"regfill{suffix}{i}"
    _c, b = req("POST", "/auth/register", body={
        "username": uname, "password": "abc12345", "realName": "限流填充",
        "phone": f"137{suffix}{i:02d}"
    })
    if b.get("code") == 200:
        created_users.append(((b.get("data") or {}).get("user") or {}).get("id"))
    else:
        # 若后端未重启、上一轮已占满窗口，这里会提前触发限流；仍继续验证"限流生效"
        print(f"  [INFO] 填充注册第 {i+1} 次返回：{str(b.get('msg'))[:40]}")
_c, b = req("POST", "/auth/register", body={
    "username": "reglimit" + suffix, "password": "abc12345", "realName": "限流验证",
    "phone": "136" + suffix + "99"
})
check("4.1 同 IP 超过窗口阈值后被限流", b.get("code") == 500 and "频繁" in str(b.get("msg")), str(b.get("msg"))[:50])

# ============ [5] 清理 ============
print("\n[5] 清理临时账号")
for uid in [u for u in created_users if u]:
    req("DELETE", f"/users/{uid}", token=at)
left = db_one(f"SELECT COUNT(*) FROM sys_user WHERE deleted=0 AND username LIKE 'reg%{suffix}%';")
check("5.1 临时注册账号已删除（逻辑删除后不再可见）", left == "0", f"left={left}")

print("\n" + "=" * 72)
print(f"批次 A 扩展测试完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 72)
sys.exit(0 if FAIL == 0 else 1)
