# -*- coding: utf-8 -*-
"""LibMS Excel 批量导入 / 报表导出回归（批次 C）

覆盖：
  [1] 导入模板：可下载、表头正确、说明页存在、读者无权限
  [2] 批量导入：合法行入库、非法 ISBN/重复/已存在/分类不存在/库存不自洽/负价/坏日期逐行返回明细
  [3] 导入护栏：读者无权限、非 Excel 文件/空文件被拒、审计留痕
  [4] 报表导出：四个工作表、汇总/明细/罚金/热门图书、权限与日期范围校验
  [5] 清理临时图书

用法（运行器会自动重置种子）：
    python test/import_export_test.py
"""
import datetime
import io
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _xlsx import make_xlsx, read_xlsx, multipart  # noqa: E402

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")
ART = os.path.join(os.path.dirname(os.path.abspath(__file__)), "artifacts")


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


def req(method, path, token=None, body=None, raw=None, content_type="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", content_type)
    if token:
        r.add_header("token", token)
    try:
        resp = urllib.request.urlopen(r, timeout=30)
        return resp.status, resp.read(), resp.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers
    except Exception as e:
        return -1, str(e).encode(), {}


def jreq(method, path, token=None, body=None, raw=None, content_type="application/json"):
    _c, data, _h = req(method, path, token, body, raw, content_type)
    try:
        return _c, json.loads(data.decode("utf-8"))
    except Exception:
        return _c, {}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def login(u, p="123456"):
    return jreq("POST", "/auth/login", body={"username": u, "password": p})[1]


print("=" * 72)
print("LibMS Excel 批量导入 / 报表导出回归（批次 C）")
print("=" * 72)

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
R = login("reader001")["data"]["token"]
suffix = str(int(time.time()))[-6:]
os.makedirs(ART, exist_ok=True)

# ============ [1] 导入模板 ============
print("\n[1] 导入模板")
_c, data, headers = req("GET", "/books/import-template", token=L)
check("1.1 模板下载成功（xlsx 文件头）",
      _c == 200 and data[:2] == b"PK" and "spreadsheetml" in headers.get("Content-Type", ""),
      f"http={_c} len={len(data)}")
check("1.2 中文文件名按 RFC 5987 编码",
      "filename*=UTF-8''" in headers.get("Content-Disposition", ""), headers.get("Content-Disposition", ""))
tpl_path = os.path.join(ART, f"template_{suffix}.xlsx")
open(tpl_path, "wb").write(data)
try:
    tpl = read_xlsx(tpl_path)
    check("1.3 模板含导入表与填写说明两个工作表",
          "图书导入模板" in tpl and "填写说明" in tpl, ",".join(tpl.keys()))
    header_row = tpl.get("图书导入模板", [[]])[0]
    check("1.4 表头覆盖 11 列业务字段",
          all(h in header_row for h in ("ISBN", "书名", "作者", "分类", "出版社",
                                        "出版日期", "定价", "总册数", "可借册数", "馆藏位置", "简介")),
          str(header_row[:11]))
except Exception as e:
    check("1.3 模板可解析", False, str(e))
    check("1.4 表头覆盖 11 列业务字段", False, "")
_c, b = jreq("GET", "/books/import-template", token=R)
check("1.5 读者下载模板 → 403", b.get("code") == 403, str(b.get("msg"))[:40])

# ============ [2] 批量导入 ============
print("\n[2] 批量导入（合法行入库 + 失败行明细）")
rows = [
    ["ISBN", "书名", "作者", "分类", "出版社", "出版日期", "定价", "总册数", "可借册数", "馆藏位置", "简介"],
    ["978" + suffix + "0001", "导入书甲", "作者甲", "计算机技术", "人民邮电出版社", "2020-01-01", 45.5, 3, 3, "A-1", "简介甲"],
    ["978" + suffix + "0002", "导入书乙", "作者乙", "文学", "人民文学出版社", "2021/5/6", 30, 2, None, "B-1", "简介乙"],
    ["123", "坏ISBN", "作者", "文学", "人民文学出版社", "2020-01-01", 1, 1, 1, "", ""],
    ["978" + suffix + "0001", "文件内重复", "作者", "文学", "人民文学出版社", "2020-01-01", 1, 1, 1, "", ""],
    ["9787111544937", "已存在", "作者", "文学", "人民文学出版社", "2020-01-01", 1, 1, 1, "", ""],
    ["978" + suffix + "0003", "坏分类", "作者", "不存在的分类", "人民文学出版社", "2020-01-01", 1, 1, 1, "", ""],
    ["978" + suffix + "0004", "库存不自洽", "作者", "文学", "人民文学出版社", "2020-01-01", 1, 2, 3, "", ""],
    ["978" + suffix + "0005", "负价", "作者", "文学", "人民文学出版社", "2020-01-01", -5, 1, 1, "", ""],
    ["978" + suffix + "0006", "坏日期", "作者", "文学", "人民文学出版社", "2020-13-40", 1, 1, 1, "", ""],
    [None, None, None, None, None, None, None, None, None, None, None],
]
import_path = os.path.join(ART, f"import_{suffix}.xlsx")
make_xlsx(import_path, rows)
content = open(import_path, "rb").read()
body, ctype = multipart("file", "import.xlsx", content,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
_c, b = jreq("POST", "/books/import", token=L, raw=body, content_type=ctype)
result = b.get("data") or {}
check("2.1 导入接口返回 200", b.get("code") == 200, str(b.get("msg"))[:60])
check("2.2 非空行总数 9（末尾空行被跳过）", result.get("total") == 9, f"total={result.get('total')}")
check("2.3 合法行成功 2 本", result.get("success") == 2, f"success={result.get('success')}")
check("2.4 失败 7 行", result.get("failed") == 7, f"failed={result.get('failed')}")
errors = result.get("errors") or []
joined = " | ".join(errors)
check("2.5 失败明细含行号与七类原因",
      all(k in joined for k in ("第 4 行", "第 5 行", "第 6 行", "第 7 行", "第 8 行", "第 9 行", "第 10 行",
                                "ISBN 格式", "文件内 ISBN 重复", "ISBN 已存在", "分类不存在",
                                "可借册数", "定价", "出版日期")),
      joined[:300])
book_a_isbn = "978" + suffix + "0001"
book_b_isbn = "978" + suffix + "0002"
db_a = db_one(f"SELECT category_id,publisher_id,total_stock,stock,price FROM book WHERE isbn='{book_a_isbn}' AND deleted=0;")
db_b = db_one(f"SELECT category_id,publisher_id,total_stock,stock FROM book WHERE isbn='{book_b_isbn}' AND deleted=0;")
check("2.6 合法行 A 分类/出版社/库存/定价正确", db_a == "1\t1\t3\t3\t45.50", str(db_a))
check("2.7 可借册数留空时默认等于总册数（B）", db_b == "2\t4\t2\t2", str(db_b))
check("2.8 非法行未入库",
      db_one(f"SELECT COUNT(*) FROM book WHERE isbn IN ('123','978{suffix}0003','978{suffix}0004','978{suffix}0005','978{suffix}0006');") == "0")
check("2.9 导入审计留痕（BOOK_IMPORT）",
      db_one("SELECT COUNT(*) FROM operation_log WHERE operation='BOOK_IMPORT';") not in (None, "0"))

# ============ [3] 导入护栏 ============
print("\n[3] 导入护栏")
_c, b = jreq("POST", "/books/import", token=R, raw=body, content_type=ctype)
check("3.1 读者批量导入 → 403", b.get("code") == 403, str(b.get("msg"))[:40])
txt_body, txt_ctype = multipart("file", "books.txt", b"not excel", "text/plain")
_c, b = jreq("POST", "/books/import", token=L, raw=txt_body, content_type=txt_ctype)
check("3.2 非 Excel 文件 → 400 且提示格式", b.get("code") == 400 and "支持" in str(b.get("msg")), str(b.get("msg"))[:40])
empty_body, empty_ctype = multipart("file", "empty.xlsx", b"",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
_c, b = jreq("POST", "/books/import", token=L, raw=empty_body, content_type=empty_ctype)
check("3.3 空文件 → 400", b.get("code") == 400, str(b.get("msg"))[:40])
# 3.4 回归防线：统一 1MB JSON 守卫不得误杀 multipart（导入上限 5MB）
big_body, big_ctype = multipart("file", "big.xlsx", b"PK\x03\x04" + os.urandom(1_200_000),
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
_c, b = jreq("POST", "/books/import", token=L, raw=big_body, content_type=big_ctype)
check("3.4 >1MB 上传到达 Excel 解析层（非 413 请求体过大）",
      b.get("code") == 500 and "解析失败" in str(b.get("msg")),
      f"code={b.get('code')} msg={str(b.get('msg'))[:40]}")

# ============ [4] 报表导出 ============
print("\n[4] 报表导出")
today = datetime.date.today()
start = (today - datetime.timedelta(days=180)).isoformat()
end = today.isoformat()
_c, data, headers = req("GET", f"/stats/export?startDate={start}&endDate={end}", token=A)
check("4.1 导出成功（xlsx 文件头）",
      _c == 200 and data[:2] == b"PK" and "spreadsheetml" in headers.get("Content-Type", ""),
      f"http={_c} len={len(data)}")
check("4.2 下载文件名按 RFC 5987 编码",
      "filename*=UTF-8''" in headers.get("Content-Disposition", ""), headers.get("Content-Disposition", ""))
export_path = os.path.join(ART, f"export_{suffix}.xlsx")
open(export_path, "wb").write(data)
try:
    wb = read_xlsx(export_path)
    check("4.3 四个工作表齐全",
          all(s in wb for s in ("汇总", "借阅明细", "罚金明细", "热门图书")), ",".join(wb.keys()))
    summary = wb.get("汇总", [])
    summary_text = " | ".join(" ".join(r) for r in summary)
    check("4.4 汇总含统计区间/图书品种/期间借出/有效预约",
          all(k in summary_text for k in ("统计区间", "图书品种", "期间借出次数", "当前有效预约")),
          summary_text[:120])
    borrow_rows = wb.get("借阅明细", [])
    check("4.5 借阅明细含表头且覆盖种子 8 条借阅",
          len(borrow_rows) == 9 and "读者证号" in borrow_rows[0] and "状态" in borrow_rows[0],
          f"rows={len(borrow_rows)}")
    fine_rows = wb.get("罚金明细", [])
    check("4.6 罚金明细含表头且覆盖种子 3 条罚金",
          len(fine_rows) == 4 and "金额（元）" in fine_rows[0] and "类型" in fine_rows[0],
          f"rows={len(fine_rows)}")
    top_rows = wb.get("热门图书", [])
    check("4.7 热门图书含排名与累计借阅列",
          len(top_rows) >= 2 and "排名" in top_rows[0] and "累计借阅次数" in top_rows[0],
          f"rows={len(top_rows)}")
except Exception as e:
    for name in ("4.3 四个工作表齐全", "4.4 汇总字段", "4.5 借阅明细", "4.6 罚金明细", "4.7 热门图书"):
        check(name, False, str(e))
_c, b = jreq("GET", f"/stats/export?startDate={end}&endDate={start}", token=A)
check("4.8 开始日期晚于结束日期 → 400", b.get("code") == 400, str(b.get("msg"))[:40])
start366 = (today - datetime.timedelta(days=365)).isoformat()
start367 = (today - datetime.timedelta(days=366)).isoformat()
_c366, data366, _h366 = req("GET", f"/stats/export?startDate={start366}&endDate={end}", token=A)
_c367, data367, _h367 = req("GET", f"/stats/export?startDate={start367}&endDate={end}", token=A)
try:
    code367 = json.loads(data367.decode("utf-8")).get("code")
except Exception:
    code367 = None
check("4.9 导出恰好 366 天允许、367 天拒绝",
      _c366 == 200 and data366[:2] == b"PK" and code367 == 400,
      f"366:http={_c366},len={len(data366)}; 367:http={_c367},code={code367}")
_c, b = jreq("GET", "/stats/export", token=L)
check("4.10 馆员导出 → 403", b.get("code") == 403, str(b.get("msg"))[:40])
_c, b = jreq("GET", "/stats/export", token=R)
check("4.11 读者导出 → 403", b.get("code") == 403, str(b.get("msg"))[:40])

# ============ [5] 清理 ============
print("\n[5] 清理临时图书")
for isbn in (book_a_isbn, book_b_isbn):
    bid = db_one(f"SELECT id FROM book WHERE isbn='{isbn}' AND deleted=0;")
    if bid:
        req("DELETE", "/books/" + str(bid), token=L)
left = db_one(f"SELECT COUNT(*) FROM book WHERE deleted=0 AND isbn IN ('{book_a_isbn}','{book_b_isbn}');")
check("5.1 临时导入图书已清理", left == "0", f"left={left}")

print("\n" + "=" * 72)
print(f"导入导出回归完成：通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 72)
sys.exit(0 if FAIL == 0 else 1)
