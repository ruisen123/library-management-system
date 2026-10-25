# -*- coding: utf-8 -*-
"""
LibMS 后端接口自动化测试脚本（标准库实现，无需第三方依赖）
覆盖：认证、用户管理、权限控制、安全、性能
运行：python api_test.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error
from urllib.parse import quote

BASE = "http://localhost:8080"
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")  # 可用环境变量覆盖（默认本地测试口令，见运行手册）


def req(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("token", token)
    t0 = time.time()
    try:
        resp = urllib.request.urlopen(r, timeout=10)
        return resp.status, resp.read().decode(), (time.time() - t0) * 1000
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), (time.time() - t0) * 1000
    except Exception as e:
        return -1, str(e), (time.time() - t0) * 1000


def j(text):
    try:
        return json.loads(text)
    except Exception:
        print("  [WARN] 响应非 JSON，回退用 HTTP 状态码判定")  # M-12：回退场景显式暴露
        return text


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


results = []


def check(cid, name, expect_code, actual_code, body, detail="", extra_ok=True):
    """判断测试是否通过：期望 code 与返回 code 一致（extra_ok 用于附加语义断言，如注入未生效）"""
    ok = (actual_code == expect_code) and extra_ok
    results.append((cid, name, expect_code, actual_code, "PASS" if ok else "FAIL", detail))
    return ok


# ============ 登录，获取 token ============
baseline_check()  # M-4：基线不符（未重置种子）时立即退出
_, r1, _ = req("POST", "/auth/login", {"username": "admin", "password": "123456"})
admin_token = j(r1).get("data", {}).get("token") if isinstance(j(r1), dict) else None
_, r2, _ = req("POST", "/auth/login", {"username": "reader001", "password": "123456"})
reader_token = j(r2).get("data", {}).get("token") if isinstance(j(r2), dict) else None

print("=" * 70)
print("【功能测试】")
# 认证模块
c, b, t = req("POST", "/auth/login", {"username": "admin", "password": "123456"})
check("TC-01", "管理员登录成功", 200, j(b).get("code") if isinstance(j(b), dict) else c,
      c, "耗时%.1fms" % t)

c, b, t = req("POST", "/auth/login", {"username": "reader001", "password": "123456"})
check("TC-02", "读者登录成功", 200, j(b).get("code") if isinstance(j(b), dict) else c,
      c, "耗时%.1fms" % t)

c, b, t = req("POST", "/auth/login", {"username": "admin", "password": "wrong"})
check("TC-03", "错误密码登录", 500, j(b).get("code") if isinstance(j(b), dict) else c,
      c, j(b).get("msg") if isinstance(j(b), dict) else b)

c, b, t = req("POST", "/auth/login", {"username": "reader002", "password": "123456"})
check("TC-04", "冻结账号登录", 500, j(b).get("code") if isinstance(j(b), dict) else c,
      c, j(b).get("msg") if isinstance(j(b), dict) else b)

c, b, t = req("POST", "/auth/login", {"username": "", "password": ""})
check("TC-05", "空参数登录校验", 400, j(b).get("code") if isinstance(j(b), dict) else c,
      c, j(b).get("msg") if isinstance(j(b), dict) else b)

# 用户列表
c, b, t = req("GET", "/users?page=1&size=10", token=admin_token)
d = j(b)
check("TC-06", "用户列表分页查询", 200, d.get("code") if isinstance(d, dict) else c, c,
      "total=%s" % (d.get("data", {}).get("total") if isinstance(d, dict) else "?"))

c, b, t = req("GET", "/users?role=READER", token=admin_token)
d = j(b)
check("TC-07", "按角色过滤(读者)", 200, d.get("code") if isinstance(d, dict) else c, c,
      "total=%s" % (d.get("data", {}).get("total") if isinstance(d, dict) else "?"))

c, b, t = req("GET", "/users?keyword=" + quote("张三"), token=admin_token)
d = j(b)
check("TC-08", "关键词检索(张三)", 200, d.get("code") if isinstance(d, dict) else c, c,
      "total=%s" % (d.get("data", {}).get("total") if isinstance(d, dict) else "?"))

c, b, t = req("GET", "/users?status=0", token=admin_token)
d = j(b)
check("TC-09", "按状态过滤(冻结)", 200, d.get("code") if isinstance(d, dict) else c, c,
      "total=%s" % (d.get("data", {}).get("total") if isinstance(d, dict) else "?"))

# 新增 / 重复
c, b, t = req("POST", "/users", {"username": "testuser1", "realName": "测试用户", "role": "READER", "gender": 1}, token=admin_token)
d = j(b)
check("TC-10", "新增用户", 200, d.get("code") if isinstance(d, dict) else c, c,
      d.get("msg") if isinstance(d, dict) else b)

c, b, t = req("POST", "/users", {"username": "admin", "realName": "重复", "role": "READER"}, token=admin_token)
d = j(b)
check("TC-11", "重复登录名校验", 500, d.get("code") if isinstance(d, dict) else c, c,
      d.get("msg") if isinstance(d, dict) else b)

# 编辑 / 冻结 / 重置密码 / 删除
c, b, t = req("GET", "/users?keyword=" + quote("testuser1"), token=admin_token)
d = j(b)
records = d.get("data", {}).get("records", []) if isinstance(d, dict) else []
tid = records[0].get("id") if records else None

c, b, t = req("PUT", "/users/%s" % tid, {"id": tid, "realName": "测试用户改", "role": "READER", "gender": 1, "phone": "13900000000"}, token=admin_token)
d = j(b)
check("TC-12", "编辑用户", 200, d.get("code") if isinstance(d, dict) else c, c,
      d.get("msg") if isinstance(d, dict) else b)

c, b, t = req("PUT", "/users/%s/status" % tid, token=admin_token)
d = j(b)
status_after = db_one("SELECT status FROM sys_user WHERE id=%s;" % tid) if tid is not None else None
check("TC-13", "冻结/启用切换", 200, d.get("code") if isinstance(d, dict) else c, c,
      (d.get("msg") if isinstance(d, dict) else b) + f" status={status_after}")
if tid is not None:
    # 冻结/启用是翻转语义：初始为 1，首次切换后必须查库为 0；否则只验 200 是空断言
    if status_after != "0":
        results[-1] = (results[-1][0], results[-1][1], results[-1][2], results[-1][3], "FAIL",
                       results[-1][5] + f"（status 未翻转：{status_after}）")

pwd_before = db_one("SELECT password FROM sys_user WHERE id=%s;" % tid) if tid is not None else None
c, b, t = req("PUT", "/users/%s/password" % tid, token=admin_token)
d = j(b)
pwd_after = db_one("SELECT password FROM sys_user WHERE id=%s;" % tid) if tid is not None else None
check("TC-14", "重置密码", 200, d.get("code") if isinstance(d, dict) else c, c,
      (d.get("msg") if isinstance(d, dict) else b) + f" hash_changed={pwd_before != pwd_after}")
if tid is not None:
    # 重置密码必须真的换哈希；只验 200 无法证明接口生效
    if not pwd_after or not pwd_after.startswith("$2") or pwd_after == pwd_before:
        results[-1] = (results[-1][0], results[-1][1], results[-1][2], results[-1][3], "FAIL",
                       results[-1][5] + "（密码哈希未变化）")

c, b, t = req("DELETE", "/users/%s" % tid, token=admin_token)
d = j(b)
deleted_after = db_one("SELECT deleted FROM sys_user WHERE id=%s;" % tid) if tid is not None else None
check("TC-15", "删除用户", 200, d.get("code") if isinstance(d, dict) else c, c,
      (d.get("msg") if isinstance(d, dict) else b) + f" deleted={deleted_after}")
if tid is not None:
    # 删除接口为逻辑删除，必须查库确认 deleted=1
    if deleted_after != "1":
        results[-1] = (results[-1][0], results[-1][1], results[-1][2], results[-1][3], "FAIL",
                       results[-1][5] + f"（deleted 未置 1：{deleted_after}）")

# 编辑不存在用户
c, b, t = req("PUT", "/users/99999", {"id": 99999, "realName": "x", "role": "READER"}, token=admin_token)
d = j(b)
check("TC-16", "编辑不存在用户", 404, d.get("code") if isinstance(d, dict) else c, c,
      d.get("msg") if isinstance(d, dict) else b)

print("=" * 70)
print("【权限控制测试】")
c, b, t = req("GET", "/users", token=reader_token)
d = j(b)
check("TC-17", "读者越权访问用户管理", 403, d.get("code") if isinstance(d, dict) else c, c,
      d.get("msg") if isinstance(d, dict) else b)

c, b, t = req("GET", "/users")
d = j(b)
check("TC-18", "未登录访问受保护接口", 401, d.get("code") if isinstance(d, dict) else c, c,
      d.get("msg") if isinstance(d, dict) else b)

print("=" * 70)
print("【安全测试】")
c, b, t = req("GET", "/users?keyword=" + quote("' OR '1'='1"), token=admin_token)
d = j(b)
inj_total = d.get("data", {}).get("total") if isinstance(d, dict) else None
check("TC-19", "SQL注入(用户检索)", 200, d.get("code") if isinstance(d, dict) else c, c,
      "total=%s(参数化安全)" % inj_total, extra_ok=(inj_total == 0))

c, b, t = req("GET", "/users?keyword=" + quote("<script>alert(1)</script>"), token=admin_token)
d = j(b)
xss_total = d.get("data", {}).get("total") if isinstance(d, dict) else None
check("TC-20", "XSS(检索关键词)", 200, d.get("code") if isinstance(d, dict) else c, c,
      "total=%s(前端渲染转义)" % xss_total, extra_ok=(xss_total == 0))

c, b, t = req("POST", "/auth/login", {"username": "admin", "password": "' OR '1'='1"})
d = j(b)
check("TC-21", "SQL注入(登录)", 500, d.get("code") if isinstance(d, dict) else c, c,
      d.get("msg") if isinstance(d, dict) else b)

print("=" * 70)
print("【性能测试】(接口响应时间)")
# M-10：阈值 1000ms——第十五轮实测登录 P99 已 498ms，500ms 阈值贴线易受环境抖动假失败（见 docs/33）
for i in range(5):
    c, b, t = req("POST", "/auth/login", {"username": "admin", "password": "123456"})
    results.append(("PF-01", "登录接口响应(第%d次)" % (i + 1), "<1000ms", "%.1fms" % t, "PASS" if t < 1000 else "FAIL", ""))
for i in range(5):
    c, b, t = req("GET", "/users?page=1&size=10", token=admin_token)
    results.append(("PF-02", "用户列表接口响应(第%d次)" % (i + 1), "<1000ms", "%.1fms" % t, "PASS" if t < 1000 else "FAIL", ""))

print("=" * 70)
total = len(results)
passed = sum(1 for r in results if r[4] == "PASS")
print("总计 %d 条，通过 %d 条，失败 %d 条，通过率 %.1f%%" % (total, passed, total - passed, passed * 100.0 / total))
# 2026-10-24（D2-17）：墙钟性能样本（PF-*）与功能断言分离统计——性能样本受机器抖动影响，
# 不是功能判据；本行格式供 run_all 解析并在报告备注中单列（对外 585 项口径不变）
func = [r for r in results if not r[0].startswith("PF-")]
perf = [r for r in results if r[0].startswith("PF-")]
print("  其中：功能断言 %d 项 / 性能样本 %d 项（PF-* 为墙钟采样，非功能判据）" % (len(func), len(perf)))
print("=" * 70)
for r in results:
    print("%s | %s | 期望:%s | 实际:%s | %s | %s" % (r[0], r[1], r[2], r[3], r[4], r[5]))
sys.exit(0 if passed == total else 1)  # M-3：失败时退出码非 0，供自动化/CI 感知
