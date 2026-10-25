# -*- coding: utf-8 -*-
"""LibMS 2026-10-23 审查修复专项验证（不计入 19 套件 585 项基线）

覆盖本次修复的关键断言：
  [1] P0：还书与"另一事务抢先推进队首"并发时，请求必须在限定时间内返回（不再空转）
  [2] ADMIN 可代办续借；READER 仍不能续借他人记录
  [3] 同一读者同一本书不可重复借阅（与预约侧口径一致）
  [4] 登录锁定期内"密码正确"仍可登录（不可被用来锁死他人账号），且锁定尝试有审计
  [5] 读者不能自助标记罚金已缴；馆员收款审计含金额
  [6] 预约资格：额度 0 / 逾期 / 欠费超限均被拒，合格读者可预约
  [7] 库存增加自动推进预约队列；库存降到低于待取数被拒
  [8] 统计占比与趋势同基准（pie 合计 = borrow_record 行数）
  [9] 上传超限返回 413 明确提示；multipart 缺 file part 返回 400

用法（需后端 8080 已启动；脚本开头/结尾都会重置种子）：
    python test/fix_verify_20261023.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common as C  # noqa: E402

BASE = "http://127.0.0.1:8080"
OPEN = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [PASS] " + name)
    else:
        FAIL += 1
        print("  [FAIL] " + name + "  " + str(detail)[:220])


def req(method, path, token=None, body=None, timeout=40, raw=None, content_type=None):
    data = raw
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["token"] = token
    r = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with OPEN.open(r, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            return e.code, {}
    except Exception as e:
        return -1, {"code": -1, "msg": "EXC:" + str(e)[:160]}


def db_one(sql):
    return C.db_one(sql)


def login(username, password="123456"):
    _c, b = req("POST", "/auth/login", body={"username": username, "password": password})
    return b


def token_of(username, password="123456"):
    b = login(username, password)
    return (b.get("data") or {}).get("token")


def multipart(fields, files):
    boundary = "----libmsfix" + str(int(time.time() * 1000))
    out = b""
    for name, value in fields.items():
        out += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                % (boundary, name, value)).encode("utf-8")
    for name, filename, content, ctype in files:
        out += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                "Content-Type: %s\r\n\r\n" % (boundary, name, filename, ctype)).encode("utf-8")
        out += content + b"\r\n"
    out += ("--%s--\r\n" % boundary).encode("utf-8")
    return out, "multipart/form-data; boundary=" + boundary


def main():
    print("=" * 74)
    print("LibMS 2026-10-23 审查修复专项验证")
    print("=" * 74)
    ok, msg = C.reset_seed()
    if not ok:
        print("  种子重置失败：" + msg)
        return 2

    at = token_of("admin")
    lt = token_of("librarian01")
    r3 = token_of("reader003")
    check("0.1 三角色令牌可用（尺子自检）", bool(at and lt and r3), "admin/librarian/reader003")

    # ============ [1] 登录锁定：正确密码不被锁死 ============
    print("\n[1] 登录锁定反作用面")
    wrong_msgs = []
    for _i in range(5):
        b = login("reader004", "wrong-pass")
        wrong_msgs.append(b.get("msg"))
    check("1.1 连续 5 次错误密码均提示凭据错误",
          all("登录名或密码错误" in str(m) for m in wrong_msgs), wrong_msgs)
    b = login("reader004", "wrong-pass")
    b_case = login("Reader004", "wrong-pass")
    b_space = login("reader004 ", "wrong-pass")
    check("1.2 锁定期内继续错密码被拒（含大小写/尾随空格变体，同一账号桶）",
          b.get("code") != 200 and "锁定" in str(b.get("msg"))
          and b_case.get("code") != 200 and "锁定" in str(b_case.get("msg"))
          and b_space.get("code") != 200 and "锁定" in str(b_space.get("msg")),
          f"exact={b.get('msg')} case={b_case.get('msg')} space={b_space.get('msg')}")
    b = login("reader004", "123456")
    check("1.3 锁定期内正确密码仍可登录（不可被锁死）", b.get("code") == 200, b.get("msg"))
    check("1.4 锁定尝试已写审计",
          int(db_one("SELECT COUNT(*) FROM operation_log WHERE operation='LOGIN_LOCKED';") or 0) >= 1,
          db_one("SELECT COUNT(*) FROM operation_log WHERE operation='LOGIN_LOCKED';"))
    r4 = token_of("reader004")

    # ============ [2] ADMIN 代办续借 ============
    print("\n[2] ADMIN 代办续借")
    old_due = db_one("SELECT due_date FROM borrow_record WHERE id=1;")
    _c, b = req("POST", "/renew", at, {"recordId": 1})
    check("2.1 管理员代读者续借成功", b.get("code") == 200, b.get("msg"))
    new_due = db_one("SELECT due_date FROM borrow_record WHERE id=1;")
    check("2.2 应还日期 +30 天", old_due and new_due and new_due > old_due, f"{old_due} -> {new_due}")
    _c, b = req("POST", "/renew", r3, {"recordId": 1})
    check("2.3 读者续借他人记录仍 403", b.get("code") == 403, b.get("code"))

    # ============ [3] 重复借同一本书 ============
    print("\n[3] 重复借阅")
    _c, b = req("POST", "/borrow", lt, {"userId": 3, "bookId": 1})
    check("3.1 已在借同一本时再次借书被拒",
          b.get("code") != 200 and "已借阅此书" in str(b.get("msg")), b.get("msg"))
    check("3.2 未新增借阅流水",
          db_one("SELECT COUNT(*) FROM borrow_record WHERE user_id=3 AND book_id=1 AND status=1;") == "1",
          db_one("SELECT COUNT(*) FROM borrow_record WHERE user_id=3 AND book_id=1 AND status=1;"))

    # ============ [4] 预约资格 ============
    print("\n[4] 预约资格（额度 / 逾期 / 欠费）")
    uname = "zfix" + str(int(time.time() * 1000))[-8:]
    req("POST", "/users", at, {"username": uname, "realName": "零额度读者", "role": "READER",
                               "gender": 0, "borrowLimit": 0, "maxUnpaid": 0, "emailNotify": 1})
    z_t = token_of(uname)
    z_id = db_one("SELECT id FROM sys_user WHERE username='%s';" % uname)
    _c, b = req("POST", "/reservations", z_t, {"bookId": 2})
    check("4.1 额度 0 的读者预约被拒（不可借者不占名额）",
          b.get("code") != 200 and "不可借" in str(b.get("msg")), b.get("msg"))
    _c, b = req("POST", "/reservations", r4, {"bookId": 2})
    check("4.2 有逾期未还的读者预约被拒",
          b.get("code") != 200 and "逾期" in str(b.get("msg")), b.get("msg"))
    _c, b = req("POST", "/reservations", r3, {"bookId": 2})
    check("4.3 欠费超限的读者预约被拒",
          b.get("code") != 200 and "罚金" in str(b.get("msg")), b.get("msg"))
    r1 = token_of("reader001")
    _c, b = req("POST", "/reservations", r1, {"bookId": 2})
    check("4.4 合格读者预约成功且直接待取",
          b.get("code") == 200 and (b.get("data") or {}).get("status") == 2, b.get("msg"))
    check("4.5 零额度读者的预约未落库",
          db_one("SELECT COUNT(*) FROM reservation WHERE user_id=%s;" % (z_id or 0)) == "0",
          db_one("SELECT COUNT(*) FROM reservation WHERE user_id=%s;" % (z_id or 0)))

    # ============ [5] 罚金收款权限与金额审计 ============
    print("\n[5] 罚金收款")
    _c, b = req("PUT", "/fines/1/pay", r3)
    check("5.1 读者自助标记罚金已缴被拒（403）", b.get("code") == 403, b.get("code"))
    check("5.2 罚金仍为未缴", db_one("SELECT status FROM fine_record WHERE id=1;") == "1",
          db_one("SELECT status FROM fine_record WHERE id=1;"))
    _c, b = req("PUT", "/fines/1/pay", lt)
    check("5.3 馆员收款成功", b.get("code") == 200, b.get("msg"))
    detail = db_one("SELECT detail FROM operation_log WHERE operation='PAY' ORDER BY id DESC LIMIT 1;") or ""
    # 注：mysql 命令行在 Windows 控制台可能按 GBK 输出中文，_common 按 UTF-8 解码会花屏；
    # 这里只断言 ASCII 可验证部分（记录 ID 与金额），中文文案由接口/人工复核。
    check("5.4 收款审计含记录 ID 与金额", "ID 1" in detail and "2.50" in detail, detail)

    # ============ [6] 库存变更联动预约队列 ============
    print("\n[6] 库存变更联动")
    isbn = "978" + str(int(time.time() * 1000))[-10:]
    req("POST", "/books", at, {"isbn": isbn, "title": "库存联动测试书", "author": "测试",
                               "categoryId": 1, "publisherId": 1, "totalStock": 0, "stock": 0})
    book_id = db_one("SELECT id FROM book WHERE isbn='%s' AND deleted=0;" % isbn)
    _c, b = req("POST", "/reservations", r1, {"bookId": int(book_id)})
    check("6.1 库存 0 时预约进入排队",
          b.get("code") == 200 and (b.get("data") or {}).get("status") == 1, b.get("msg"))
    _c, b = req("PUT", "/books/%s" % book_id, at, {
        "isbn": isbn, "title": "库存联动测试书", "author": "测试",
        "categoryId": 1, "publisherId": 1, "totalStock": 1, "stock": 1})
    check("6.2 管理员增加库存成功", b.get("code") == 200, b.get("msg"))
    check("6.3 库存增加后队首自动提升为待取",
          db_one("SELECT status FROM reservation WHERE book_id=%s ORDER BY id DESC LIMIT 1;" % book_id) == "2",
          db_one("SELECT status FROM reservation WHERE book_id=%s ORDER BY id DESC LIMIT 1;" % book_id))
    check("6.4 提升后生成到书通知",
          int(db_one("SELECT COUNT(*) FROM notification WHERE user_id=3 AND type=1;") or 0) >= 1,
          db_one("SELECT COUNT(*) FROM notification WHERE user_id=3 AND type=1;"))
    _c, b = req("PUT", "/books/%s" % book_id, at, {
        "isbn": isbn, "title": "库存联动测试书", "author": "测试",
        "categoryId": 1, "publisherId": 1, "totalStock": 0, "stock": 0})
    check("6.5 库存降到低于待取数被拒",
          b.get("code") != 200 and "待取预约数" in str(b.get("msg")), b.get("msg"))

    # ============ [7] 统计同基准 ============
    print("\n[7] 统计口径")
    _c, b = req("GET", "/stats/pie", at)
    pie = b.get("data") or []
    pie_sum = sum(int(x.get("value") or 0) for x in pie)
    br_cnt = int(db_one("SELECT COUNT(*) FROM borrow_record;") or 0)
    check("7.1 分类占比合计 = 借阅流水行数（与趋势同基准）", pie_sum == br_cnt, f"pie={pie_sum} rows={br_cnt}")
    _c, b = req("GET", "/stats/trend", at)
    trend_sum = sum(int(x or 0) for x in ((b.get("data") or {}).get("values") or []))
    check("7.2 趋势合计 = 借阅流水行数", trend_sum == br_cnt, f"trend={trend_sum} rows={br_cnt}")

    # ============ [8] 上传错误提示 ============
    print("\n[8] 上传错误提示")
    body, ctype = multipart({}, [("file", "big.xlsx", b"0" * (6 * 1024 * 1024),
                                   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")])
    _s, b = req("POST", "/books/import", lt, raw=body, content_type=ctype, timeout=60)
    check("8.1 超过 5MB 返回 413 且提示文件过大",
          b.get("code") == 413 and "过大" in str(b.get("msg")), f"code={b.get('code')} msg={b.get('msg')}")
    body, ctype = multipart({"note": "no-file"}, [])
    _s, b = req("POST", "/books/import", lt, raw=body, content_type=ctype)
    check("8.2 缺少 file part 返回 400 且提示选择文件",
          b.get("code") == 400 and "请选择" in str(b.get("msg")), f"code={b.get('code')} msg={b.get('msg')}")

    # ============ [9] P0：还书与外部推进并发 ============
    print("\n[9] P0 空转保护（还书 + 另一事务抢先推进队首）")
    book = 6
    C.db_multi("DELETE FROM reservation WHERE book_id=%d;" % book)
    C.db_multi("DELETE FROM borrow_record WHERE book_id=%d AND user_id=3 AND status=1 AND borrow_date=CURDATE();" % book)
    C.db_multi("UPDATE book SET stock=1 WHERE id=%d;" % book)
    C.db_multi("INSERT INTO reservation (book_id,user_id,queue_no,status,ready_time,expire_time,create_time,update_time) "
               "VALUES (%d,5,1,2,NOW(),DATE_ADD(NOW(),INTERVAL 7 DAY),NOW(),NOW());" % book)
    C.db_multi("INSERT INTO reservation (book_id,user_id,queue_no,status,create_time,update_time) "
               "VALUES (%d,6,2,1,NOW(),NOW());" % book)
    C.db_multi("INSERT INTO borrow_record (user_id,book_id,status,borrow_date,due_date,renew_count,create_time,update_time) "
               "VALUES (3,%d,1,CURDATE(),DATE_ADD(CURDATE(),INTERVAL 30 DAY),0,NOW(),NOW());" % book)
    rid = db_one("SELECT id FROM borrow_record WHERE book_id=%d AND user_id=3 AND status=1 ORDER BY id DESC LIMIT 1;" % book)
    qwait = db_one("SELECT id FROM reservation WHERE book_id=%d AND queue_no=2;" % book)
    mysql = C.mysql_exe()
    lock = subprocess.Popen([mysql, "-uroot", "-p" + C.DB_PWD, C.DB_NAME],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8")
    lock.stdin.write("START TRANSACTION;\nSELECT id FROM book WHERE id=%d FOR UPDATE;\n" % book)
    lock.stdin.flush()
    time.sleep(1.0)
    result = {}

    def do_return():
        result["ret"] = req("POST", "/return", lt, {"recordId": int(rid)}, timeout=30)

    th = __import__("threading").Thread(target=do_return)
    th.start()
    time.sleep(2.0)
    C.db_multi("UPDATE reservation SET status=2, ready_time=NOW(), "
               "expire_time=DATE_ADD(NOW(),INTERVAL 7 DAY) WHERE id=%s;" % qwait)
    lock.stdin.write("ROLLBACK;\n")
    lock.stdin.flush()
    lock.terminate()
    th.join(15)
    check("9.1 还书请求在 15 秒内返回（不再空转）", not th.is_alive(), "thread still alive")
    ret = result.get("ret") or (-1, {})
    check("9.2 还书业务成功且库存回补",
          ret[1].get("code") == 200 and db_one("SELECT stock FROM book WHERE id=%d;" % book) == "2",
          f"code={ret[1].get('code')} stock={db_one('SELECT stock FROM book WHERE id=%d;' % book)}")

    # ============ [11] 个人中心手机号唯一性 ============
    print("\n[11] 个人中心手机号唯一性")
    sfx = str(int(time.time()))[-6:]
    p1 = "139" + str(int(time.time()))[-8:]
    p2 = "138" + str(int(time.time()))[-8:]
    u1 = "fixph1" + sfx
    u2 = "fixph2" + sfx
    _c, b1 = req("POST", "/users", at, {"username": u1, "password": "abc12345",
                                         "realName": "手机号A", "role": "READER", "gender": 0, "phone": p1})
    _c, b2 = req("POST", "/users", at, {"username": u2, "password": "abc12345",
                                         "realName": "手机号B", "role": "READER", "gender": 0, "phone": p2})
    check("11.1 创建两个临时读者", b1.get("code") == 200 and b2.get("code") == 200,
          f"{b1.get('msg')}/{b2.get('msg')}")
    t2 = token_of(u2, "abc12345")
    _c, b3 = req("PUT", "/profile", t2, {"realName": "手机号B", "gender": 0,
                                          "phone": p1, "email": "", "emailNotify": 1})
    check("11.2 个人中心改他人手机号被拒",
          b3.get("code") != 200 and "手机号已存在" in str(b3.get("msg")),
          f"code={b3.get('code')} msg={b3.get('msg')}")

    # ============ [12] 请求 ID 注入防护 ============
    print("\n[12] 请求 ID 注入防护")
    r = urllib.request.Request(BASE + "/auth/login",
                               data=json.dumps({"username": "admin", "password": "123456"}).encode("utf-8"),
                               method="POST",
                               headers={"Content-Type": "application/json", "X-Request-Id": "bad id"})
    try:
        with OPEN.open(r, timeout=20) as resp:
            rid = resp.headers.get("X-Request-Id") or ""
    except Exception as e:
        rid = "ERR:" + str(e)[:60]
    check("12.1 非法 X-Request-Id 被替换为 16 位生成值",
          len(rid) == 16 and rid != "bad id", rid)

    # ============ 收尾 ============
    print("\n[10] 收尾")
    ok, msg = C.reset_seed()
    check("10.1 种子重置成功", ok, msg)
    bad = C.baseline_ok()
    check("10.2 基线计数恢复（无残留探针数据）", bad == [], bad)

    print("\n" + "=" * 74)
    print("专项验证完成：通过 %d 项，失败 %d 项" % (PASS, FAIL))
    print("=" * 74)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
