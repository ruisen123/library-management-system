
# -*- coding: utf-8 -*-
"""
LibMS 创新点②③专项测试（M-1 证据补齐）
② 生成列唯一索引：逻辑删除后同名可复用，未删除时仍唯一
③ 密码指纹令牌失效：改密后旧令牌立即失效

约定（与项目既有套件一致）：净零副作用——测试数据在 finally 中重置种子清理
"""
import json, os, sys, subprocess, urllib.request, urllib.error
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common as C

BASE = "http://localhost:8080"
PASS = 0; FAIL = 0

def req(method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token: r.add_header("token", token)
    try:
        resp = urllib.request.urlopen(r, timeout=30)
        return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try: return json.loads(e.read().decode("utf-8"))
        except Exception: return {"code": e.code, "msg": "HTTP %d" % e.code}
    except Exception as e:
        return {"code": -1, "msg": "EXC:" + str(e)}

def check(name, cond, detail=""):
    global PASS, FAIL
    if cond: PASS += 1; print("  [PASS] " + name)
    else: FAIL += 1; print("  [FAIL] " + name + "  " + str(detail))

def admin_token():
    r = req("POST", "/auth/login", body={"username": "admin", "password": "123456"})
    return (r.get("data") or {}).get("token")

def librarian_token():
    # 图书写操作授权给图书管理员与系统管理员
    # （2026-10-21 权限模型调整：管理员⊇馆员，管理员也可增删改图书）
    r = req("POST", "/auth/login", body={"username": "librarian01", "password": "123456"})
    return (r.get("data") or {}).get("token")

def reader_token():
    # 读者无图书写权限，用于验证权限边界仍然生效（不受管理员权限放宽影响）
    r = req("POST", "/auth/login", body={"username": "reader001", "password": "123456"})
    return (r.get("data") or {}).get("token")

def mk_user(tok, username, password, reader_no, phone="13800000000"):
    body = {"username": username, "password": password, "realName": "生成列测试",
            "role": "READER", "readerNo": reader_no, "gender": 0, "phone": phone}
    return req("POST", "/users", token=tok, body=body)

def uid_by_name(name):
    v = C.db_one("SELECT id FROM sys_user WHERE username='%s' ORDER BY id DESC LIMIT 1;" % name)
    return v

print("=" * 74)
print("LibMS 创新点②③专项测试（生成列唯一索引 / 密码指纹令牌失效）")
print("=" * 74)

ok, msg = C.reset_seed()
print("[准备] 种子重置：%s" % msg)
tok = admin_token()
check("管理员登录成功", bool(tok))

# ---------- ② 生成列唯一索引：逻辑删除后同名可复用 ----------
print("\n[②-1] 未删除时同名仍唯一（uk_username 生效）")
r1 = mk_user(tok, "gen_test_u", "abc12345", "GEN001")
check("首次创建 gen_test_u 成功", r1.get("code") == 200, r1.get("msg"))
id1 = uid_by_name("gen_test_u")
r2 = mk_user(tok, "gen_test_u", "abc12345", "GEN002", phone="13800000010")
check("同名重复创建被唯一约束拒绝", r2.get("code") != 200, "code=%s msg=%s" % (r2.get("code"), r2.get("msg")))
uk1 = C.db_one("SELECT uk_username FROM sys_user WHERE id=%s;" % id1)
check("未删除行 uk_username 非空 = gen_test_u", uk1 == "gen_test_u", "实际=%r" % uk1)

print("\n[②-2] 逻辑删除后同名可复用（生成列为 NULL，不占唯一名额）")
rdel = req("DELETE", "/users/%s" % id1, token=tok)
check("逻辑删除成功", rdel.get("code") == 200, rdel.get("msg"))
uk_del = C.db_one("SELECT uk_username FROM sys_user WHERE id=%s;" % id1)
# 2026-10-24（D2-10）：不再接受 None/""——db_one 在 mysql 不可用或 SQL 失败时返回 None，
# 原写法把"查询失败"也算成"生成列为 NULL"（真空通过）；"" 在 mysql -N 下也不是 NULL 的表现形式
check("已删除行 uk_username 变为 NULL", uk_del == "NULL", "实际=%r" % uk_del)
r3 = mk_user(tok, "gen_test_u", "abc12345", "GEN003", phone="13800000011")
check("删除后可用同名重建", r3.get("code") == 200, r3.get("msg"))
id2 = uid_by_name("gen_test_u")
check("新记录与旧记录 id 不同", id2 is not None and id2 != id1, "old=%s new=%s" % (id1, id2))
cnt = C.db_one("SELECT COUNT(*) FROM sys_user WHERE uk_username='gen_test_u';")
check("同名未删除行始终只有 1 条", cnt == "1", "实际=%s" % cnt)

print("\n[②-3] 图书 ISBN 同机制（写操作需管理员或图书管理员）")
ltok = librarian_token()
check("图书管理员登录成功", bool(ltok))
book = {"isbn": "9787111999999", "title": "生成列测试图书", "author": "测试",
        "categoryId": 1, "publisherId": 1, "publishDate": "2020-01-01", "price": 10.0,
        "totalStock": 1, "stock": 1, "location": "T-01", "description": "测试"}
rtok = reader_token()
b0 = req("POST", "/books", token=rtok, body=book)
check("读者建图书被拒（权限边界）", b0.get("code") == 403, "code=%s" % b0.get("code"))
b1 = req("POST", "/books", token=ltok, body=book)
check("首次创建图书成功", b1.get("code") == 200, b1.get("msg"))
bid = C.db_one("SELECT id FROM book WHERE isbn='9787111999999' ORDER BY id DESC LIMIT 1;")
b2 = req("POST", "/books", token=ltok, body=book)
check("同 ISBN 重复创建被拒", b2.get("code") != 200, "code=%s" % b2.get("code"))
bd = req("DELETE", "/books/%s" % bid, token=ltok)
check("逻辑删除图书成功", bd.get("code") == 200, bd.get("msg"))
uk_isbn = C.db_one("SELECT uk_isbn FROM book WHERE id=%s;" % bid)
# 2026-10-24（D2-10）：同上，查询失败（None）不再算通过
check("已删除图书 uk_isbn 变为 NULL", uk_isbn == "NULL", "实际=%r" % uk_isbn)
b3 = req("POST", "/books", token=ltok, body=book)
check("删除后可用同 ISBN 重建", b3.get("code") == 200, b3.get("msg"))

print("\n[②-3b] 权限模型调整后：系统管理员可建图书（管理员⊇馆员）")
adbook = dict(book)
adbook["isbn"] = "9787111888888"
ba = req("POST", "/books", token=tok, body=adbook)
check("系统管理员建图书成功", ba.get("code") == 200, ba.get("msg"))
adid = C.db_one("SELECT id FROM book WHERE isbn='9787111888888' ORDER BY id DESC LIMIT 1;")
if adid:
    req("DELETE", "/books/%s" % adid, token=tok)

# ---------- ③ 密码指纹令牌失效 ----------
print("\n[③-1] 改密前：令牌有效")
m1 = mk_user(tok, "pwd_test_u", "abc12345", "PWD001", phone="13800000012")
check("创建测试读者成功", m1.get("code") == 200, m1.get("msg"))
pid = uid_by_name("pwd_test_u")
lg = req("POST", "/auth/login", body={"username": "pwd_test_u", "password": "abc12345"})
old_tok = (lg.get("data") or {}).get("token")
check("读者用初始密码登录成功", bool(old_tok), lg.get("msg"))
pf1 = req("GET", "/profile", token=old_tok)
check("旧令牌可正常访问 /profile", pf1.get("code") == 200, pf1.get("msg"))

print("\n[③-2] 改密后：旧令牌立即失效（无需吊销名单）")
rs = req("PUT", "/users/%s/password" % pid, token=tok)
check("管理员重置该用户密码成功", rs.get("code") == 200, rs.get("msg"))
pf2 = req("GET", "/profile", token=old_tok)
check("旧令牌立即失效（401）", pf2.get("code") == 401, "code=%s msg=%s" % (pf2.get("code"), pf2.get("msg")))
lg2 = req("POST", "/auth/login", body={"username": "pwd_test_u", "password": "123456"})
new_tok = (lg2.get("data") or {}).get("token")
check("新密码可登录", bool(new_tok), lg2.get("msg"))
pf3 = req("GET", "/profile", token=new_tok)
check("新令牌可正常访问", pf3.get("code") == 200, pf3.get("msg"))
lg3 = req("POST", "/auth/login", body={"username": "pwd_test_u", "password": "abc12345"})
check("原密码已不可用", lg3.get("code") != 200, "code=%s" % lg3.get("code"))

print("\n[③-3] 令牌篡改防护（对照组，证明 401 是校验所致而非恰好过期）")
bad_tok = (old_tok or "")[:-4] + "AAAA"
pf4 = req("GET", "/profile", token=bad_tok)
check("篡改令牌被拒（401）", pf4.get("code") == 401, "code=%s" % pf4.get("code"))

# ---------- 清理 ----------
ok2, msg2 = C.reset_seed()
bad = C.baseline_ok()
print("\n[清理] 种子重置：%s；10 表基线：%s" % (msg2, "OK" if not bad else bad))

print("\n" + "=" * 74)
print("创新点②③专项测试完成：通过 %d 项，失败 %d 项" % (PASS, FAIL))
print("=" * 74)
sys.exit(0 if FAIL == 0 else 1)
