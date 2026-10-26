# -*- coding: utf-8 -*-
"""LibMS 全业务流程端到端测试（覆盖 docs/03 §2.2 全部用例 + §3.1~3.3 异常分支）

用法（**必须先重置种子**）：
    mysql -u root -p --default-character-set=utf8mb4 < docs/04_数据库设计.sql
    python test/e2e_full_flow.py

说明：
  · 断言包含"接口返回码/字段"与"数据库层不变量"两类；mysql 客户端不可用时 DB 断言优雅 SKIP。
  · 编号与 docs/03 用例一一对应；阶段 0 为传输层校验（gzip），阶段 7 为终态数据一致性 + 审计覆盖。
  · 与 smoke_test*/api_test 的区别：本脚本按"用例清单全量跑通业务链路"，而非按接口逐个探测。
"""
import datetime
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://localhost:8080"
MYSQL_CANDIDATES = ("mysql", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")  # 可用环境变量覆盖（默认本地测试口令，见运行手册）
RESULTS = []


def db(sql):
    """直查数据库返回单值；mysql 不可用返回 None（用于数据层不变量断言）。

    说明：每次调用都会 spawn 一个 mysql 子进程，偶发启动失败（非零退出/超时）会让断言误报，
    故对"进程级失败"重试 3 次（仅重试进程失败，不影响"查询成功但结果为 0"的语义）。
    """
    import time as _t
    for cmd in MYSQL_CANDIDATES:
        for attempt in range(3):
            try:
                r = subprocess.run([cmd, "-uroot", "-p" + DB_PWD, "-N", "-e", "USE library_db; " + sql],
                                   capture_output=True, text=True, encoding="utf-8",
                                   errors="replace", timeout=20)
                if r.returncode == 0:
                    return (r.stdout or "").strip()
            except Exception:
                pass
            _t.sleep(0.3)
    return None


def api(method, path, token=None, body=None, extra_headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("token", token)
    for k, v in (extra_headers or {}).items():
        req.add_header(k, v)
    try:
        resp = urllib.request.urlopen(req, timeout=15)
        return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}
    except Exception as ex:
        return -1, {"msg": str(ex)}


def api_headers(method, path, token=None, extra_headers=None):
    """返回响应头字典（同名头以逗号合并，避免 Vary 等多值头被覆盖）"""
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("token", token)
    for k, v in (extra_headers or {}).items():
        req.add_header(k, v)

    def pack(hdrs):
        out = {}
        for k, v in hdrs.items():
            lk = k.lower()
            out[lk] = out[lk] + ", " + v if lk in out else v
        return out

    try:
        resp = urllib.request.urlopen(req, timeout=15)
        return pack(resp.headers)
    except urllib.error.HTTPError as e:
        return pack(e.headers)
    except Exception:
        return {}


def flow(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  → {detail}" if detail else ""))


def login(u, p="123456"):
    _, b = api("POST", "/auth/login", body={"username": u, "password": p})
    return (b.get("data", {}) or {}).get("token") if b.get("code") == 200 else None, b


def code(b):
    return b.get("code") if isinstance(b, dict) else None


def msg(b):
    return str(b.get("msg", ""))[:46] if isinstance(b, dict) else ""


def inv_stock():
    return db("SELECT COUNT(*) FROM book b WHERE (b.total_stock-b.stock)<>(SELECT COUNT(*) FROM borrow_record br WHERE br.book_id=b.id AND br.status IN(1,3));")


def inv_count():
    return db("SELECT COUNT(*) FROM sys_user u WHERE u.role='READER' AND u.borrowed_count<>(SELECT COUNT(*) FROM borrow_record br WHERE br.user_id=u.id AND br.status=1);")


def log_count():
    v = db("SELECT COUNT(*) FROM operation_log;")
    return int(v) if v else 0


def log_has(op):
    v = db(f"SELECT COUNT(*) FROM operation_log WHERE operation='{op}';")
    return int(v) if v else 0


def baseline_check():
    """种子基线校验（M-4）：防"上一轮脚本污染未重置"导致的静默缩量/误判。
    mysql 不可用时跳过校验（打印提示，不阻断）。"""
    expect = {"sys_user": 6, "category": 4, "publisher": 4, "book": 6,
              "borrow_record": 8, "fine_record": 3, "notice": 3, "operation_log": 3}
    bad = []
    for table, want in expect.items():
        got = db(f"SELECT COUNT(*) FROM {table};")
        if got is None:
            print("  [SKIP] mysql 不可用，跳过种子基线校验")
            return
        if got != str(want):
            bad.append(f"{table}={got}(期望{want})")
    if bad:
        print(f"[BASELINE FAIL] 种子基线不符：{' '.join(bad)}")
        print("  请先重置种子（mysql < docs/04_数据库设计.sql）后重跑本脚本")
        sys.exit(1)
    print("种子基线校验通过：用户=6 分类=4 出版社=4 图书=6 借阅=8 罚金=3 公告=3 日志=3")


print("=" * 78)
print("LibMS 全业务流程端到端测试（docs/03 用例清单全量）")
print("=" * 78)
baseline_check()  # M-4：基线不符（未重置种子）时立即退出，防止顺序污染的静默误判
print(f"不变量基线: 库存自洽异常行={inv_stock()} 在借数漂移行={inv_count()}")

# ============ 阶段 0：传输层（gzip）============
print("\n【阶段 0】传输层压缩")
# 注意：必须带 token——无 token 会返回 401，其响应体远小于压缩阈值（2048B），测不出压缩
_tk0, _ = login("librarian01")
h_plain = api_headers("GET", "/books?page=1&size=10", token=_tk0)
h_gzip = api_headers("GET", "/books?page=1&size=10", token=_tk0, extra_headers={"Accept-Encoding": "gzip"})
flow("0.1 声明 gzip 时返回 Content-Encoding: gzip",
     h_gzip.get("content-encoding") == "gzip", str(h_gzip.get("content-encoding")))
flow("0.2 未声明 gzip 时不压缩（脚本/旧客户端行为不变）",
     "content-encoding" not in h_plain, str(h_plain.get("content-encoding")))
flow("0.3 响应带 Vary: accept-encoding（缓存正确性）",
     "accept-encoding" in (h_gzip.get("vary", "") + h_plain.get("vary", "")),
     str(h_gzip.get("vary")))

# ============ 阶段 1：登录鉴权 ============
print("\n【阶段 1】登录鉴权（公共用例）")
TK = {}
for u, label in (("admin", "ADMIN"), ("librarian01", "LIBRARIAN"), ("reader001", "READER")):
    t, b = login(u)
    TK[label] = t
    flow(f"1.{list(TK).index(label)+1} {label} 登录成功", t is not None and code(b) == 200, msg(b))
_, b = login("reader001", "wrongpw")
flow("1.4 错误口令被拒", code(b) == 500, msg(b))
_, b = login("reader002")
flow("1.5 冻结账号被拒", code(b) == 500 and "冻结" in msg(b), msg(b))
_, b = login("nobody999")
flow("1.6 不存在账号被拒", code(b) == 500, msg(b))
flow("1.7 登录留痕(LOGIN)", log_has("LOGIN") >= 3, f"LOGIN={log_has('LOGIN')}")
flow("1.8 失败登录留痕(LOGIN_FAIL: 错误口令+冻结+不存在共 3 次)",
     log_has("LOGIN_FAIL") >= 3, f"LOGIN_FAIL={log_has('LOGIN_FAIL')}")
flow("1.9 冻结账号登录尝试亦留痕（R15 修复点）",
     int(db("SELECT COUNT(*) FROM operation_log WHERE operation='LOGIN_FAIL' AND detail LIKE '%账号已冻结%';") or 0) >= 1)

A, L, R = TK["ADMIN"], TK["LIBRARIAN"], TK["READER"]

# ============ 阶段 2：读者自助流程 ============
print("\n【阶段 2】读者自助流程（READER）")
_, b = api("GET", "/books?page=1&size=10", L)
flow("2.1 多条件分页检索（列表）", code(b) == 200 and len(b["data"]["records"]) > 0)
_, b = api("GET", "/books?keyword=" + quote("算法"), L)
flow("2.2 关键词检索命中", code(b) == 200 and b["data"]["total"] >= 1, f"total={(b.get('data') or {}).get('total')}")
_, b = api("GET", "/books?categoryId=1", L)
flow("2.3 分类筛选", code(b) == 200)
_, b = api("GET", "/books/1", R)
flow("2.4 图书详情查看", code(b) == 200 and b["data"]["title"] is not None)
_, b = api("GET", "/my-borrows?page=1&size=100", R)
onl = [r for r in (b.get("data") or {}).get("records", []) if r.get("status") == 1]
flow("2.5 我的借阅-在借列表", code(b) == 200 and len(onl) >= 1, f"在借={len(onl)}")
_, b = api("GET", "/my-borrows?page=1&size=100", R)
hist = [r for r in (b.get("data") or {}).get("records", []) if r.get("status") == 2]
flow("2.6 我的借阅-历史记录", code(b) == 200 and len(hist) >= 1, f"已还={len(hist)}")
_, b = api("GET", "/fines?page=1&size=10", R)
mine = (b.get("data") or {}).get("records", [])
# 注：reader001 此刻尚无罚金（空集），"仅本人"的非空真值验证由 5.12d 承担（M-5 修复）
flow("2.7 个人罚金查询（仅本人）", code(b) == 200 and all(r.get("userId") == 3 for r in mine), f"条数={len(mine)}")
_, b = api("GET", "/profile", R)
flow("2.8 个人资料读取", code(b) == 200 and b["data"]["username"] == "reader001")
_, b = api("PUT", "/profile", R, {"realName": "张三", "gender": 1, "phone": "13900000003"})
flow("2.9 个人资料修改", code(b) == 200)
flow("2.9b 资料修改留痕(UPDATE_PROFILE)", log_has("UPDATE_PROFILE") >= 1)
_, b = api("PUT", "/profile/password", R, {"oldPassword": "123456", "newPassword": "e2ePass1"})
flow("2.10 密码修改", code(b) == 200)
# R19-6：改密后旧令牌立即失效（密码指纹机制）——以下两处 R 刷新为配套适配
_, b_oldtk = api("GET", "/profile", R)
flow("2.10a 改密后旧令牌立即失效（R19-6）", code(b_oldtk) == 401, msg(b_oldtk))
t_new, b_new = login("reader001", "e2ePass1")
flow("2.10b 新口令可登录", t_new is not None and code(b_new) == 200)
R = t_new  # 旧 R 已失效，切换为新令牌继续后续用例
_, b_old = login("reader001", "123456")
flow("2.10c 旧口令已失效", code(b_old) == 500, msg(b_old))
api("PUT", "/profile/password", R, {"oldPassword": "e2ePass1", "newPassword": "123456"})
flow("2.10d 改密码留痕(UPDATE_PASSWORD)", log_has("UPDATE_PASSWORD") >= 1)
R, _ = login("reader001")  # 改回密码再次使上一令牌失效，刷新令牌供后续阶段使用

# ============ 阶段 3：管理员流程 ============
print("\n【阶段 3】管理员流程（ADMIN）")
_, b = api("GET", "/users?page=1&size=10", A)
flow("3.1 读者账号分页检索", code(b) == 200)
_, b = api("POST", "/users", A, {"username": "e2e_reader", "realName": "端到端读者", "role": "READER", "gender": 1, "readerNo": "E2E0001"})
flow("3.2 新增读者开户", code(b) == 200, msg(b))
new_uid = db("SELECT id FROM sys_user WHERE username='e2e_reader';")
flow("3.2b 新增留痕(USER_ADD)", log_has("USER_ADD") >= 1)
_, b = api("POST", "/users", A, {"username": "e2e_lib", "realName": "端到端馆员", "role": "LIBRARIAN"})
flow("3.3 新增图书管理员账号", code(b) == 200, msg(b))
_, b = api("POST", "/users", A, {"username": "e2e_reader", "realName": "重复", "role": "READER"})
flow("3.4 重复登录名被拒", code(b) == 500, msg(b))
_, b = api("PUT", f"/users/{new_uid}/status", A)
flow("3.5 读者停用（冻结）", code(b) == 200)
_, b = api("PUT", f"/users/{new_uid}/status", A)
flow("3.5b 读者启用", code(b) == 200)
flow("3.5c 停启用留痕含结果态与账号（R15 修复点）",
     int(db("SELECT COUNT(*) FROM operation_log WHERE operation='USER_STATUS' AND (detail LIKE '冻结用户[%' OR detail LIKE '启用用户[%');") or 0) >= 2)
_, b = api("PUT", f"/users/{new_uid}/password", A)
flow("3.6 重置密码", code(b) == 200)
t_r, b_r = login("e2e_reader")
flow("3.6b 重置后可用初始口令登录", t_r is not None and code(b_r) == 200)
_, b = api("PUT", f"/users/{new_uid}", A, {"id": int(new_uid), "realName": "端到端读者改", "role": "READER", "gender": 2, "phone": "13900000009"})
flow("3.7 编辑用户", code(b) == 200, msg(b))
_, b = api("PUT", "/users/1/status", A)
flow("3.8 自冻结被拒（防自伤）", code(b) == 500, msg(b))
_, b = api("DELETE", "/users/1", A)
flow("3.9 自删除被拒", code(b) == 500, msg(b))

_, b = api("GET", "/categories", A)
flow("3.11 分类列表", code(b) == 200)
_, b = api("POST", "/categories", A, {"name": "E2E分类", "sort": 5})
flow("3.12 新增分类", code(b) == 200, msg(b))
cid = db("SELECT id FROM category WHERE name='E2E分类';")
_, b = api("PUT", f"/categories/{cid}", A, {"id": int(cid), "name": "E2E分类改", "sort": 6})
flow("3.13 修改分类", code(b) == 200, msg(b))
_, b = api("DELETE", "/categories/1", A)
flow("3.14 分类下有图书时禁删", code(b) == 500, msg(b))
_, b = api("DELETE", f"/categories/{cid}", A)
flow("3.15 删除空分类", code(b) == 200, msg(b))
flow("3.15b 分类增删改留痕", log_has("CATEGORY_ADD") >= 1 and log_has("CATEGORY_UPDATE") >= 1 and log_has("CATEGORY_DELETE") >= 1)

_, b = api("GET", "/publishers", A)
flow("3.16 出版社列表", code(b) == 200)
_, b = api("POST", "/publishers", A, {"name": "E2E出版社", "contact": "010-1234", "address": "测试地址"})
flow("3.17 新增出版社", code(b) == 200, msg(b))
pid = db("SELECT id FROM publisher WHERE name='E2E出版社';")
_, b = api("PUT", f"/publishers/{pid}", A, {"id": int(pid), "name": "E2E出版社改", "contact": "021-5678", "address": "改后地址"})
flow("3.18 修改出版社", code(b) == 200, msg(b))
_, b = api("DELETE", "/publishers/1", A)
flow("3.19 出版社名下有书时禁删", code(b) == 500, msg(b))
_, b = api("DELETE", f"/publishers/{pid}", A)
flow("3.20 删除空出版社", code(b) == 200, msg(b))

_, b = api("GET", "/notices?page=1&size=10", A)
flow("3.21 公告列表", code(b) == 200)
_, b = api("POST", "/notices", A, {"title": "E2E公告", "content": "端到端测试公告"})
flow("3.22 发布公告", code(b) == 200, msg(b))
nid = db("SELECT id FROM notice WHERE title='E2E公告';")
_, b = api("PUT", f"/notices/{nid}", A, {"title": "E2E公告", "content": "改", "status": 0})
flow("3.23 撤回公告", code(b) == 200, msg(b))
flow("3.23b 公告留痕(ADD/UPDATE)", log_has("NOTICE_ADD") >= 1 and log_has("NOTICE_UPDATE") >= 1)
_, b = api("GET", "/notices?page=1&size=10", R)
pub23 = b["data"]["records"] if code(b) == 200 else []
flow("3.23c 已撤回公告对读者不可见（列表非空且全部已发布）",
     code(b) == 200 and len(pub23) >= 1
     and all(r.get("status") == 1 for r in pub23)
     and all(r.get("id") != int(nid) for r in pub23),
     f"条数={len(pub23)}")
_, b = api("DELETE", f"/notices/{nid}", A)
flow("3.24 删除公告", code(b) == 200)
_, b = api("GET", "/notices?page=1&size=10", R)
recs25 = (b.get("data") or {}).get("records", [])
flow("3.25 读者只见已发布公告", code(b) == 200 and len(recs25) >= 1 and all(r.get("status") == 1 for r in recs25), f"条数={len(recs25)}")
_, b = api("GET", "/rule/config", A)
flow("3.26 借阅规则只读展示", code(b) == 200 and b["data"]["maxBorrow"] == 5)
_, b = api("GET", "/rule/config", R)
# 2026-10-22 权限调整：/rule/config 只有 GET（规则固化在后端常量 FineConstant），
# 对三角色开放只读 —— 馆员要能向读者解释规则、读者要能自己查。
# 原断言「读者被拒 403」属旧权限模型，已作废；改为校验读者能读到真实规则值。
flow(
    "3.26b 读者可读借阅规则（只读开放）",
    code(b) == 200 and b["data"]["maxBorrow"] == 5 and b["data"]["borrowDays"] == 30,
    msg(b),
)
_, b = api("GET", "/stats/overview", A)
flow("3.27 统计总览", code(b) == 200 and b["data"]["bookKinds"] >= 6)
_, b = api("GET", "/stats/trend", A)
flow("3.28 月度借阅趋势", code(b) == 200 and len(b["data"]["months"]) == 12)
_, b = api("GET", "/stats/pie", A)
flow("3.29 分类占比", code(b) == 200 and len(b["data"]) >= 1)
_, b = api("GET", "/stats/top", A)
flow("3.30 借阅 TOP10", code(b) == 200 and len(b["data"]) >= 1)
_, b = api("GET", "/logs?page=1&size=10", A)
flow("3.31 操作日志查询", code(b) == 200 and b["data"]["total"] >= 10, f"total={(b.get('data') or {}).get('total')}")
_, b = api("GET", "/logs?page=1&size=10", L)
flow("3.31b 馆员访问日志被拒", code(b) == 403, msg(b))

# ============ 阶段 4：图书档案管理 ============
print("\n【阶段 4】图书档案管理（LIBRARIAN）")
_, b = api("GET", "/books?page=1&size=10&keyword=" + quote("算法"), L)
flow("4.1 图书多条件检索", code(b) == 200)
_, b = api("POST", "/books", L, {"isbn": "9780000000101", "title": "E2E图书", "author": "作者", "categoryId": 1, "publisherId": 1, "totalStock": 2, "stock": 2, "price": 30})
flow("4.2 新增图书", code(b) == 200, msg(b))
bid = db("SELECT id FROM book WHERE title='E2E图书';")
flow("4.2b 新增图书留痕(BOOK_ADD)", log_has("BOOK_ADD") >= 1)
_, b = api("POST", "/books", L, {"isbn": "9780000000101", "title": "重复ISBN", "author": "x", "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
flow("4.3 重复 ISBN 被拒", code(b) == 500, msg(b))
_, b = api("POST", "/books", L, {"isbn": "9780000000102", "title": "不自洽", "author": "x", "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 5})
flow("4.4 库存不自洽被拒（可借>总册）", code(b) == 500, msg(b))
_, b = api("POST", "/books", L, {"isbn": "978000E2E999", "title": "非法ISBN", "author": "x", "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
flow("4.4b 非法 ISBN 格式被拒", code(b) == 400, msg(b))
_, b = api("PUT", f"/books/{bid}", L, {"isbn": "9780000000101", "title": "E2E图书改", "author": "作者", "categoryId": 1, "publisherId": 1, "totalStock": 3, "stock": 3, "price": 35})
flow("4.5 编辑图书", code(b) == 200, msg(b))
flow("4.5b 编辑留痕(BOOK_UPDATE)", log_has("BOOK_UPDATE") >= 1)

# ============ 阶段 5：借还办理全流程 ============
print("\n【阶段 5】借还办理全流程（LIBRARIAN）")
log_before_borrow = int(db("SELECT COUNT(*) FROM operation_log WHERE operation='BORROW';"))
_, b = api("POST", "/borrow", L, {"userId": 3, "bookId": int(bid)})
flow("5.1 借书成功（正常路径）", code(b) == 200, msg(b))
rid = db(f"SELECT id FROM borrow_record WHERE user_id=3 AND book_id={bid} AND status=1;")
flow("5.1b 库存扣减 3→2", db(f"SELECT stock FROM book WHERE id={bid};") == "2")
flow("5.1c 借书留痕(BORROW)", int(db("SELECT COUNT(*) FROM operation_log WHERE operation='BORROW';")) == log_before_borrow + 1)
_, b = api("GET", "/readers?keyword=" + quote("张三"), L)
flow("5.1d 读者检索（借书前置）", code(b) == 200 and len(b["data"]) >= 1)
_, b = api("POST", "/borrow", L, {"userId": 4, "bookId": int(bid)})
flow("5.2 冻结账号借书被拒", code(b) == 500 and "冻结" in msg(b), msg(b))
_, b = api("POST", "/borrow", L, {"userId": 5, "bookId": int(bid)})
flow("5.3 有未缴罚金读者借书被拒", code(b) == 500 and "罚金" in msg(b), msg(b))
_, b = api("POST", "/borrow", L, {"userId": 6, "bookId": int(bid)})
flow("5.3b 有逾期未还读者借书被拒", code(b) == 500 and "逾期" in msg(b), msg(b))
_, b = api("POST", "/borrow", L, {"userId": 3, "bookId": 3})
flow("5.3c 库存为 0 借书被拒", code(b) == 500, msg(b))
_, b = api("POST", "/borrow", L, {"userId": 3, "bookId": 999999})
flow("5.3d 借不存在图书被拒（严格 404 语义）", code(b) == 404, msg(b))
_, b = api("POST", "/borrow", L, {"userId": 3})
flow("5.3e 缺参数被拒", code(b) == 400, msg(b))
_, b = api("POST", "/renew", L, {"recordId": int(rid)})
flow("5.4 续借成功（首次，+30 天）", code(b) == 200, msg(b))
flow("5.4b 续借留痕(RENEW)", log_has("RENEW") >= 1)
_, b = api("POST", "/renew", L, {"recordId": int(rid)})
flow("5.5 二次续借被拒（每册限 1 次）", code(b) == 500, msg(b))
_, b = api("POST", "/renew", L, {"recordId": 5})
flow("5.5b 逾期记录续借被拒", code(b) == 500, msg(b))
_, b = api("POST", "/fines/compensate", L, {"recordId": int(rid), "amount": 20, "type": 2, "remark": "E2E赔偿登记"})
flow("5.6 赔偿登记（破损）", code(b) == 200, msg(b))
flow("5.6b 赔偿留痕(COMPENSATE)", log_has("COMPENSATE") >= 1)
_, b = api("POST", "/return", L, {"recordId": int(rid)})
flow("5.7 已赔偿记录重复还书被拒", code(b) == 500, msg(b))
log_before_return = int(db("SELECT COUNT(*) FROM operation_log WHERE operation='RETURN';"))
_, b = api("POST", "/return", L, {"recordId": 1})
flow("5.8 还书成功（含逾期判定）", code(b) == 200, msg(b))
flow("5.8b 还书留痕(RETURN)", int(db("SELECT COUNT(*) FROM operation_log WHERE operation='RETURN';")) == log_before_return + 1)
flow("5.8c 还书留痕含书名与读者（M22-1 审计粒度）",
     int(db("SELECT COUNT(*) FROM operation_log WHERE operation='RETURN' AND detail LIKE '%办理还书：《深入理解计算机系统（第3版）》%';")) >= 1)
_, b = api("POST", "/return", L, {"recordId": 1})
flow("5.9 重复还书被拒", code(b) == 500, msg(b))
# 缴清赔偿产生的未缴罚金（否则后续借书会被"欠费禁借"正确拦截）
comp_fine = db(f"SELECT id FROM fine_record WHERE borrow_id={rid} AND status=1;")
_, b = api("PUT", f"/fines/{comp_fine}/pay", L)
flow("5.9b 缴清赔偿罚金（解除欠费禁借）", db(f"SELECT status FROM fine_record WHERE id={comp_fine};") == "2", msg(b))
# 逾期还书 → 自动登记罚金
_, b = api("POST", "/borrow", L, {"userId": 3, "bookId": int(bid)})
flow("5.10 借书（缴清后额度恢复）", code(b) == 200, msg(b))
rid2 = db(f"SELECT id FROM borrow_record WHERE user_id=3 AND book_id={bid} AND status=1;")
# 新库有 CHECK(due_date >= borrow_date)：模拟逾期时借出日期也要同步前移，
# 否则测试数据本身不合法（不能为了造逾期把还期改到借期之前）。
db(f"UPDATE borrow_record SET borrow_date=DATE_SUB(CURDATE(), INTERVAL 34 DAY), "
   f"due_date=DATE_SUB(CURDATE(), INTERVAL 4 DAY) WHERE id={rid2};")
_, b = api("POST", "/return", L, {"recordId": int(rid2)})
ov = (b.get("data") or {}).get("overdueDays") if code(b) == 200 else None
amt = (b.get("data") or {}).get("fineAmount") if code(b) == 200 else None
flow("5.11 逾期还书自动登记罚金（4 天 × 0.5 = 2.00 元）",
     code(b) == 200 and str(ov) == "4" and str(amt) in ("2.00", "2.0"), f"天数={ov} 金额={amt}")
newfine = db(f"SELECT id FROM fine_record WHERE borrow_id={rid2} AND status=1;")
_, b = api("PUT", f"/fines/{newfine}/pay", L)
flow("5.12 罚金标记已缴", code(b) == 200, msg(b))
flow("5.12b 缴费留痕(PAY)", log_has("PAY") >= 1)
_, b = api("PUT", f"/fines/{newfine}/pay", L)
flow("5.12c 重复缴费被拒", code(b) == 500, msg(b))
_, b = api("GET", "/fines?page=1&size=10", R)
mine_r = (b.get("data") or {}).get("records", [])
flow("5.12d 读者罚金仅本人（非空真值验证，补足 2.7 的空集场景）",
     code(b) == 200 and len(mine_r) >= 1 and all(r.get("userId") == 3 for r in mine_r),
     f"条数={len(mine_r)}")
_, b = api("GET", "/fines?page=1&size=10", L)
flow("5.13 罚金管理列表", code(b) == 200)
_, b = api("GET", "/borrow-records?page=1&size=10", L)
flow("5.14 借阅记录查询（全量流水）", code(b) == 200 and b["data"]["total"] >= 8)
_, b = api("GET", "/borrow-records?page=1&size=10&status=1", L)
flow("5.14b 按状态筛选（结果非空）", code(b) == 200 and b["data"]["total"] >= 1 and all(r.get("status") == 1 for r in b["data"]["records"]), msg(b))
_d0 = datetime.date.today().isoformat()  # 5.14c（第十九轮补实）：动态区间，防固定年份随种子相对化而失真
_d180 = (datetime.date.today() - datetime.timedelta(days=180)).isoformat()
_, b = api("GET", f"/borrow-records?page=1&size=10&startDate={_d180}&endDate={_d0}", L)
flow("5.14c 按日期区间筛选（动态区间含全部记录）",
     code(b) == 200 and b["data"]["total"] >= 1, f"total={(b.get('data') or {}).get('total')}")

# ============ 阶段 6：删除护栏与逻辑删除复用 ============
print("\n【阶段 6】删除护栏与逻辑删除复用")
# M-2 修复：动态取一条在借记录的图书（不依赖具体种子 id），对"在借禁删"护栏做真实断言
onloan_bid = db("SELECT book_id FROM borrow_record WHERE status=1 LIMIT 1;")
_, b = api("DELETE", f"/books/{onloan_bid}", L)
flow("6.1 有在借记录图书禁删（真实护栏断言）", code(b) == 500, msg(b))
_, b = api("POST", "/books", L, {"isbn": "9780000000103", "title": "待删图书", "author": "x", "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
bid3 = db("SELECT id FROM book WHERE title='待删图书';")
_, b = api("DELETE", f"/books/{bid3}", L)
flow("6.2 无在借图书可删除", code(b) == 200, msg(b))
flow("6.2b 删除留痕(BOOK_DELETE)", log_has("BOOK_DELETE") >= 1)
_, b = api("POST", "/books", L, {"isbn": "9780000000103", "title": "复用同ISBN", "author": "x", "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
flow("6.3 逻辑删除后 ISBN 可复用", code(b) == 200, msg(b))
_, b = api("DELETE", f"/users/{new_uid}", A)
flow("6.4 删除无业务往来的读者", code(b) == 200, msg(b))
flow("6.4b 删除留痕(USER_DELETE)", log_has("USER_DELETE") >= 1)
_, b = api("POST", "/users", A, {"username": "e2e_reader", "realName": "复用登录名", "role": "READER"})
flow("6.5 逻辑删除后登录名可复用", code(b) == 200, msg(b))

# ============ 阶段 7：终态数据一致性与审计覆盖 ============
print("\n【阶段 7】终态数据一致性 + 审计覆盖")
flow("7.1 库存自洽不变量（total = stock + 在借 + 已赔偿）", inv_stock() == "0", f"异常行={inv_stock()}")
flow("7.2 读者在借数冗余字段与流水一致", inv_count() == "0", f"漂移行={inv_count()}")
flow("7.3 借阅记录无孤儿外键",
     db("SELECT COUNT(*) FROM borrow_record br LEFT JOIN book b ON br.book_id=b.id LEFT JOIN sys_user u ON br.user_id=u.id WHERE b.id IS NULL OR u.id IS NULL;") == "0")
flow("7.4 罚金记录均关联借阅记录",
     db("SELECT COUNT(*) FROM fine_record f LEFT JOIN borrow_record br ON f.borrow_id=br.id WHERE br.id IS NULL;") == "0")
flow("7.5 操作日志累计条目", log_count() >= 25, f"共 {log_count()} 条")
OPS = ["LOGIN", "LOGIN_FAIL", "BORROW", "RETURN", "RENEW", "PAY", "COMPENSATE", "RESET_PASSWORD",
       "BOOK_ADD", "BOOK_UPDATE", "BOOK_DELETE", "CATEGORY_ADD", "CATEGORY_UPDATE", "CATEGORY_DELETE",
       "PUBLISHER_ADD", "PUBLISHER_UPDATE", "PUBLISHER_DELETE", "NOTICE_ADD", "NOTICE_UPDATE", "NOTICE_DELETE",
       "USER_ADD", "USER_UPDATE", "USER_STATUS", "USER_DELETE", "UPDATE_PROFILE", "UPDATE_PASSWORD"]
# 一次查询取回全部操作码（原实现对 26 个操作码逐个 spawn mysql 子进程，偶发进程失败会造成误判）
present = db("SELECT GROUP_CONCAT(DISTINCT operation) FROM operation_log;") or ""
present_set = set(present.split(",")) if present.strip() else set()
missing = [o for o in OPS if o not in present_set]
flow(f"7.6 全流程审计覆盖（{len(OPS)} 类写操作均留痕）", not missing, f"缺失={missing}")

print("\n" + "=" * 78)
passed = sum(1 for _, ok, _ in RESULTS if ok)
print(f"全流程实跑结果：通过 {passed} 项，失败 {len(RESULTS) - passed} 项")
for n, ok, d in RESULTS:
    if not ok:
        print(f"  ✗ {n}  {d}")
print("=" * 78)
sys.exit(0 if passed == len(RESULTS) else 1)
