# -*- coding: utf-8 -*-
"""LibMS 安全专项回归测试（第二十一轮新增常驻套件）

沉淀第二十一轮三个新视角的防线（此前回归未覆盖）：
  [1] 日志注入防护——外部输入（CRLF/控制字符）经写端点后，审计 detail 不得含换行（防伪造日志行）
  [2] 错误信息不泄露——各类异常响应的 msg 不得含技术细节（堆栈/类名/表名/SQL 片段）
  [3] 数值边界——负数/超范围数值被拒（金额与库存字段）

用法（运行器会自动重置种子）：python test/security_test.py
"""
import base64
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


def req(method, path, token=None, body=None, raw=None):
    data = raw.encode("utf-8") if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
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


print("=" * 72)
print("LibMS 安全专项回归测试（日志注入 / 错误信息 / 数值边界）")
print("=" * 72)
baseline_check()

_, _b = req("POST", "/auth/login", body={"username": "admin", "password": "123456"})
AT = (_b.get("data") or {}).get("token")
_, _b = req("POST", "/auth/login", body={"username": "librarian01", "password": "123456"})
LT = (_b.get("data") or {}).get("token")
_, _b = req("POST", "/auth/login", body={"username": "reader001", "password": "123456"})
RT = (_b.get("data") or {}).get("token")

# ============ [1] 日志注入防护 ============
print("\n[1] 日志注入防护（审计 detail 不得含控制字符）")
inj = "安测注入\r\n2026-01-01 00:00:00 WARN  伪造行"
_c, b = req("POST", "/categories", AT, {"name": inj, "sort": 98})
check("1.1 含 CRLF 的分类名可正常创建（业务不因输入被拒）", b.get("code") == 200, str(b.get("msg"))[:40])
if db_one("SELECT COUNT(*) FROM category WHERE name LIKE '安测注入%';") is None:
    print("  [SKIP] mysql 不可用，跳过 1.2/1.3")
else:
    n_lf = db_one("SELECT COUNT(*) FROM operation_log WHERE operation='CATEGORY_ADD' AND detail LIKE CONCAT('%', CHAR(10), '%');")
    n_cr = db_one("SELECT COUNT(*) FROM operation_log WHERE operation='CATEGORY_ADD' AND detail LIKE CONCAT('%', CHAR(13), '%');")
    check("1.2 审计 detail 无换行(0x0A)——sanitize 生效（防伪造日志行）", n_lf == "0", f"含LF行数={n_lf}")
    check("1.3 审计 detail 无回车(0x0D)", n_cr == "0", f"含CR行数={n_cr}")

spe = "特殊'\"`;--<script>x</script>"
_c, b = req("POST", "/publishers", AT, {"name": spe, "contact": "c", "address": "a"})
check("1.4 特殊字符输入可入库且接口正常（SQL 参数化 + 前端转义面）", b.get("code") == 200, str(b.get("msg"))[:40])

# ============ [2] 错误信息不泄露 ============
print("\n[2] 错误信息不泄露（msg 不含技术细节）")
LEAK = ["java.", "Exception", "at com.", "at org.", "springframework", "library_db",
        "SELECT ", "INSERT ", "UPDATE ", "table ", "column ", "SQLException", "NullPointer"]
cases = [
    ("2.1 非法 JSON", "POST", "/auth/login", None, "{bad json"),
    ("2.2 类型错误", "POST", "/books", LT, json.dumps({"isbn": "9790000000701", "title": "t", "author": "a", "categoryId": 1, "publisherId": 1, "totalStock": "abc", "stock": 1})),
    ("2.3 缺必填字段", "POST", "/books", LT, json.dumps({"title": "t"})),
    ("2.4 超长字段", "POST", "/categories", AT, json.dumps({"name": "x" * 300, "sort": 1})),
    ("2.5 不存在资源", "GET", "/books/88888888", LT, None),
    ("2.6 非法枚举值", "PUT", "/users/999999", AT, json.dumps({"realName": "x", "role": "HACKER"})),
    ("2.7 负数金额", "POST", "/fines/compensate", LT, json.dumps({"recordId": 1, "amount": -5, "type": 2})),
    ("2.8 无效令牌", "GET", "/profile", "INVALID_TOKEN_XYZ", None),
]
for name, m, p, tk, raw in cases:
    _c, b = req(m, p, tk, raw=raw)
    msg = str(b.get("msg", ""))
    leaked = [w for w in LEAK if w in msg]
    check(name, not leaked, f"msg={msg[:60]!r} 泄露={leaked}")

none_token = (base64.urlsafe_b64encode(b'{"alg":"none"}').rstrip(b"=").decode()
              + "." + base64.urlsafe_b64encode(b'{"sub":"1","role":"ADMIN"}').rstrip(b"=").decode() + ".")
_c, b = req("GET", "/profile", none_token)
check("2.9 alg:none 伪造令牌被拒（算法混淆防护）", b.get("code") == 401, f"code={b.get('code')}")

# ============ [3] 数值边界 ============
print("\n[3] 数值边界（金额与库存字段）")
_c, b = req("POST", "/books", LT, {"isbn": "9790000000702", "title": "数界A", "author": "a", "categoryId": 1,
                                   "publisherId": 1, "totalStock": 1, "stock": 1, "price": -5})
check("3.1 定价负数被拒", b.get("code") == 400, f"code={b.get('code')} msg={str(b.get('msg'))[:30]}")
_c, b = req("POST", "/books", LT, {"isbn": "9790000000703", "title": "数界B", "author": "a", "categoryId": 1,
                                   "publisherId": 1, "totalStock": -1, "stock": 0})
check("3.2 库存负数被拒（400 参数校验）", b.get("code") == 400,
      f"code={b.get('code')} msg={str(b.get('msg'))[:30]}")
_c, b = req("POST", "/books", LT, {"isbn": "9790000000704", "title": "数界C", "author": "a", "categoryId": 1,
                                   "publisherId": 1, "totalStock": 99999999999, "stock": 1})
check("3.3 库存超 INT 范围被拒（400）", b.get("code") == 400, f"code={b.get('code')}")

# ============ [4] 清理 ============
print("\n[4] 清理本套件数据")
_c, cl = req("GET", "/categories", AT)
cid = next((c["id"] for c in (cl.get("data") or []) if str(c.get("name", "")).startswith("安测注入")), None)
if cid:
    req("DELETE", f"/categories/{cid}", AT)
_c, pl = req("GET", "/publishers", AT)
pid = next((p["id"] for p in (pl.get("data") or []) if str(p.get("name", "")).startswith("特殊'")), None)
if pid:
    req("DELETE", f"/publishers/{pid}", AT)
left_c = db_one("SELECT COUNT(*) FROM category WHERE deleted=0 AND name LIKE '安测注入%';")
if left_c is None:
    print("  [SKIP] mysql 不可用，跳过清理核对")
else:
    # ⚠️ 必须显式加 deleted=0：删除接口为逻辑删除（行仍在表内），否则计数恒不为零
    left_p = db_one("SELECT COUNT(*) FROM publisher WHERE deleted=0 AND name LIKE '特殊\\'%';")
    check("4.1 临时分类与出版社清理完毕（净零副作用；逻辑删除口径）", left_c == "0" and left_p == "0", f"cat={left_c} pub={left_p}")

# ============ [5] 越权（IDOR）与审计账实（第二十三轮沉淀）============
print("\n[5] 越权（IDOR）与审计账实")
_c, b = req("GET", "/my-borrows?page=1&size=100&userId=4", RT)
recs = (b.get("data") or {}).get("records") or []
uids = {r.get("userId") for r in recs}
# 2026-10-24（D2-10）：补"非空"——空集 ⊆ {3} 恒真，查询失败/无数据也会算通过
check("5.1 读者带他人 userId 查询我的借阅 → 参数被忽略（非空且仅本人）",
      b.get("code") == 200 and len(recs) >= 1 and uids == {3}, f"recs={len(recs)} uids={uids}")

other_rec = db_one("SELECT id FROM borrow_record WHERE user_id=4 AND status=1 LIMIT 1;")
if other_rec is None:
    print("  [SKIP] mysql 不可用，跳过 5.2")
else:
    # 2026-10-24（D2-11）归因修正：/return 与 /fines/compensate 的 @RequireRole 不含 READER，
    # 原来的"读者还书/赔偿他人记录 → 403"是**角色拦截**，删掉服务层归属校验照样绿——
    # 不能当归属断言。这里拆成：① 真归属断言（READER 本身有续借权，403 只能来自归属校验）；
    # ② 显式标注为角色边界；真"有权角色 + 他人资源"的正向归属断言见 5.6。
    _c, b = req("POST", "/renew", RT, {"recordId": int(other_rec)})
    check("5.2a 读者续借他人记录 → 403（归属校验；READER 本身有续借权）",
          b.get("code") == 403, f"code={b.get('code')} msg={str(b.get('msg'))[:30]}")
    _c, b1 = req("POST", "/return", RT, {"recordId": int(other_rec)})
    _c, b2 = req("POST", "/fines/compensate", RT, {"recordId": int(other_rec), "amount": 1, "type": 2})
    check("5.2b 读者还书/赔偿他人记录 → 403（角色边界，非归属断言）",
          b1.get("code") == 403 and b2.get("code") == 403,
          f"return={b1.get('code')} compensate={b2.get('code')}")
    other_fine = db_one("SELECT id FROM fine_record WHERE user_id=5 AND status=1 LIMIT 1;")
    if other_fine:
        _c, b = req("PUT", f"/fines/{other_fine}/pay", RT)
        check("5.3 读者缴他人罚金 → 403（2026-10-23 起 pay 仅 ADMIN+LIBRARIAN，角色边界）",
              b.get("code") == 403, f"code={b.get('code')}")

# 审计账实：成功写操作恰好 +1 条日志（临时读者/书，自清理）
_, b = req("POST", "/users", AT, {"username": "secidor", "realName": "越权账实", "role": "READER"})
_c, bl = req("GET", "/users?keyword=secidor", AT)
tuid = next((r["id"] for r in ((bl.get("data") or {}).get("records") or []) if r["username"] == "secidor"), None)
_, b = req("POST", "/books", LT, {"isbn": "9790000000901", "title": "账实测试书", "author": "a",
                                  "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
_c, gb = req("GET", "/books?keyword=9790000000901", LT)
tbid = ((gb.get("data") or {}).get("records") or [{}])[0].get("id")
c0 = db_one("SELECT COUNT(*) FROM operation_log;")
_c, b = req("POST", "/borrow", LT, {"userId": tuid, "bookId": tbid})
c1 = db_one("SELECT COUNT(*) FROM operation_log;")
if c0 is None or c1 is None:
    print("  [SKIP] mysql 不可用，跳过 5.4 审计账实")
else:
    check("5.4 借书成功恰好产生 1 条审计日志（账实一致）",
          b.get("code") == 200 and int(c1) - int(c0) == 1, f"code={b.get('code')} delta={int(c1) - int(c0)}")
    c0 = db_one("SELECT COUNT(*) FROM operation_log;")
    req("POST", "/borrow", LT, {"userId": tuid, "bookId": 999999})
    c1 = db_one("SELECT COUNT(*) FROM operation_log;")
    check("5.5 失败操作不产生审计日志（+0 条）", int(c1) - int(c0) == 0, f"delta={int(c1) - int(c0)}")

# 清理 [5] 段数据
rid = db_one(f"SELECT id FROM borrow_record WHERE user_id={tuid} AND status=1 LIMIT 1;") if tuid else None
if rid is not None and tbid is not None:
    # 2026-10-24（D2-11）：真·"有权角色 + 他人资源"归属断言——LIBRARIAN 替临时读者（他人）
    # 还书必须成功，且作用对象就是那条记录本身（status 1→2 已还 + 库存回补），
    # 而不是"读者被角色拦下 403"这种证不了归属校验的假断言
    stock_b4 = db_one(f"SELECT stock FROM book WHERE id={int(tbid)};")
    rc, rb = req("POST", "/return", LT, {"recordId": int(rid)})
    ret_status = db_one(f"SELECT status FROM borrow_record WHERE id={int(rid)};")
    stock_af = db_one(f"SELECT stock FROM book WHERE id={int(tbid)};")
    check("5.6 馆员替他人还书成功且作用对象正确（状态 1→2 已还 + 库存回补）",
          rc == 200 and rb.get("code") == 200 and ret_status == "2"
          and stock_b4 is not None and stock_af == str(int(stock_b4) + 1),
          f"code={rb.get('code')} status={ret_status} stock={stock_b4}->{stock_af}")
else:
    check("5.6 馆员替他人还书成功且作用对象正确（状态 1→2 已还 + 库存回补）",
          False, f"无临时借阅记录或图书（rid={rid} tbid={tbid}）")
req("DELETE", f"/books/{tbid}", LT)
req("DELETE", f"/users/{tuid}", AT)
check("5.7 [5] 段临时数据清理完毕",
      db_one("SELECT COUNT(*) FROM book WHERE deleted=0 AND isbn='9790000000901';") == "0"
      and db_one("SELECT COUNT(*) FROM sys_user WHERE deleted=0 AND username='secidor';") == "0")

print("\n" + "=" * 72)
print(f"安全专项测试完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 72)
sys.exit(0 if FAIL == 0 else 1)
