
# -*- coding: utf-8 -*-
"""巡检规则双向验证：防漏报（注入异常→必须发现）+ 防误报（合法边界→必须不报）。

用法：
  set LIBMS_DB_PWD=***                     # 必填：测试库口令（不提供默认值）
  python test/perf/verify_audit_rules.py
  python test/perf/verify_audit_rules.py --dry-run

环境变量：
  LIBMS_DB_PWD  必填，测试库口令
  LIBMS_MYSQL   可选，mysql 客户端路径；缺省从 PATH 查找

注意：脚本会创建并 DROP 探针库 library_audit_probe2，只应对测试库执行。"""
import os, subprocess, sys, shutil
sys.stdout.reconfigure(encoding='utf-8')

def _find_root():
    """向上回溯定位项目根（含 docs/04_数据库设计.sql），使脚本在任意子目录下均可运行。"""
    d = os.path.dirname(os.path.abspath(__file__))
    for _ in range(6):
        if os.path.isfile(os.path.join(d, "docs", "04_数据库设计.sql")):
            return d
        d = os.path.dirname(d)
    raise RuntimeError("未找到项目根目录（缺少 docs/04_数据库设计.sql）")

ROOT = _find_root()
SEED = os.path.join(ROOT, "docs", "04_数据库设计.sql")
MYSQL = os.environ.get("LIBMS_MYSQL") or shutil.which("mysql")
PROBE = "library_audit_probe2"
PWD_ = os.environ.get("LIBMS_DB_PWD")

if "--dry-run" in sys.argv:
    print("[DRY-RUN] 探针库=%s；将注入 14 类异常 + 4 类合法边界，预期防漏报 14/14、防误报 4/4；结束后 DROP 探针库" % PROBE)
    sys.exit(0)
if not MYSQL:
    print("[SKIP] 未找到 mysql 客户端：请设置 LIBMS_MYSQL 或把 mysql 加入 PATH")
    sys.exit(0)
if not PWD_:
    print("[SKIP] 未设置 LIBMS_DB_PWD（测试库口令）；为避免口令硬编码，脚本不提供默认值")
    sys.exit(0)

def run(sql, db=None, stdin_file=None):
    cmd = [MYSQL, "-uroot", "-p" + PWD_, "--default-character-set=utf8mb4", "-N", "-B"]
    if db: cmd.append(db)
    if stdin_file:
        with open(stdin_file, "rb") as f:
            return subprocess.run(cmd, stdin=f, capture_output=True, text=True,
                                  encoding="utf-8", errors="replace", timeout=180)
    cmd += ["-e", sql]
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=60)

def probe_once(inject_sql, check_sql):
    """单连接内：注入 → 检查 → 回滚。返回检查值。"""
    sql = f"USE {PROBE}; START TRANSACTION; {inject_sql} SELECT {check_sql} AS v; ROLLBACK;"
    r = run(sql)
    out = (r.stdout or "").strip().splitlines()
    return out[-1].strip() if out else "ERR:" + (r.stderr or "")[:80]

sql_text = open(SEED, encoding="utf-8").read()
tmp = os.path.join(ROOT, "test", "perf", "_probe_seed2.sql")
open(tmp, "w", encoding="utf-8").write(sql_text.replace("library_db", PROBE))

C_R1  = "(SELECT COUNT(*) FROM book b WHERE (b.total_stock-b.stock)<>(SELECT COUNT(*) FROM borrow_record br WHERE br.book_id=b.id AND br.status IN(1,3)))"
C_R2  = "(SELECT COUNT(*) FROM sys_user u WHERE u.role='READER' AND u.borrowed_count<>(SELECT COUNT(*) FROM borrow_record br WHERE br.user_id=u.id AND br.status=1))"
C_R3  = "(SELECT COUNT(*) FROM borrow_record WHERE status=1 AND return_date IS NOT NULL)"
C_R4  = "(SELECT COUNT(*) FROM borrow_record WHERE status=2 AND (return_date IS NULL OR return_date<borrow_date))"
C_R5  = "(SELECT COUNT(*) FROM borrow_record WHERE due_date<borrow_date)"
C_R6  = "(SELECT COUNT(*) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.user_id<>br.user_id)"
C_R7  = "(SELECT COUNT(*) FROM fine_record WHERE (status=2 AND paid_time IS NULL) OR (status=1 AND paid_time IS NOT NULL))"
C_R8  = "(SELECT COUNT(*) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.type=1 AND br.return_date IS NOT NULL AND f.amount<>ROUND(DATEDIFF(br.return_date,br.due_date)*0.5,2))"
C_R9a = "(SELECT COUNT(*) FROM borrow_record br WHERE br.status=3 AND NOT EXISTS(SELECT 1 FROM fine_record f WHERE f.borrow_id=br.id AND f.type IN(2,3)))"
C_R9b = "(SELECT COUNT(*) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.type IN(2,3) AND br.status<>3)"
C_R10a= "(SELECT COUNT(*) FROM book b WHERE b.deleted=1 AND EXISTS(SELECT 1 FROM borrow_record br WHERE br.book_id=b.id AND br.status=1))"
C_R10b= "(SELECT COUNT(*) FROM sys_user u WHERE u.deleted=1 AND (EXISTS(SELECT 1 FROM borrow_record br WHERE br.user_id=u.id AND br.status=1) OR EXISTS(SELECT 1 FROM fine_record f WHERE f.user_id=u.id AND f.status=1)))"
C_R10c= "(SELECT COUNT(*) FROM category c WHERE c.deleted=1 AND EXISTS(SELECT 1 FROM book b WHERE b.category_id=c.id AND b.deleted=0))"
C_R10d= "(SELECT COUNT(*) FROM publisher p WHERE p.deleted=1 AND EXISTS(SELECT 1 FROM book b WHERE b.publisher_id=p.id AND b.deleted=0))"

try:
    run(f"DROP DATABASE IF EXISTS {PROBE};")
    r = run(f"CREATE DATABASE {PROBE} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;")
    r = run("", db=PROBE, stdin_file=tmp)
    assert r.returncode == 0, r.stderr[:400]
    print("[OK] 临时库就绪\n")

    print("=" * 72)
    print("A. 防漏报：注入一个异常 → 规则必须发现（期望 >0）")
    print("=" * 72)
    leaks = [
      ("R1  库存自洽",    "UPDATE book SET stock=stock-1 WHERE id=1;", C_R1),
      ("R2  在借数一致",  "UPDATE sys_user SET borrowed_count=borrowed_count+1 WHERE id=3;", C_R2),
      ("R3  在借无归还日","UPDATE borrow_record SET return_date=CURDATE() WHERE id=1;", C_R3),
      ("R4  已还有归还日","UPDATE borrow_record SET return_date=NULL WHERE id=3;", C_R4),
      ("R5  日期顺序",    "UPDATE borrow_record SET due_date=DATE_SUB(borrow_date, INTERVAL 1 DAY) WHERE id=1;", C_R5),
      ("R6  罚金归属",    "UPDATE fine_record SET user_id=3 WHERE id=1;", C_R6),
      ("R7  缴纳配套",    "UPDATE fine_record SET paid_time=NULL WHERE id=2;", C_R7),
      ("R8  金额可重算",  "UPDATE fine_record SET amount=99.00 WHERE id=1;", C_R8),
      ("R9a 赔偿有罚金",  "DELETE FROM fine_record WHERE id=3;", C_R9a),
      ("R9b 罚金有赔偿",  "UPDATE fine_record SET type=2 WHERE id=1;", C_R9b),
      ("R10a 已删书在借", "UPDATE book SET deleted=1 WHERE id=1;", C_R10a),
      ("R10b 已删用户",   "UPDATE sys_user SET deleted=1 WHERE id=5;", C_R10b),
      ("R10c 已删分类",   "UPDATE category SET deleted=1 WHERE id=1;", C_R10c),
      ("R10d 已删出版社", "UPDATE publisher SET deleted=1 WHERE id=1;", C_R10d),
    ]
    ok = 0
    for name, inj, chk in leaks:
        v = probe_once(inj, chk)
        good = v.isdigit() and int(v) > 0
        ok += 1 if good else 0
        print("  %-18s 发现异常行数 = %-6s %s" % (name, v, "✓" if good else "✗ 漏报!"))

    print("\n" + "=" * 72)
    print("B. 防误报：合法但'像异常'的数据 → 规则必须不报（期望 =0）")
    print("=" * 72)
    # A: 已删图书引用已删分类（合法历史）→ R10c 不得报
    injA = ("INSERT INTO category(id,name,sort) VALUES(901,'探针分类',9);"
            "INSERT INTO book(id,isbn,title,author,category_id,publisher_id,total_stock,stock,borrow_count) "
            "VALUES(901,'PROBE-ISBN-901','探针书','探针',901,1,1,1,0);"
            "UPDATE book SET deleted=1 WHERE id=901;"
            "UPDATE category SET deleted=1 WHERE id=901;")
    # B: 已删图书引用已删出版社 → R10d 不得报
    injB = ("INSERT INTO publisher(id,name) VALUES(902,'探针出版社');"
            "INSERT INTO book(id,isbn,title,author,category_id,publisher_id,total_stock,stock,borrow_count) "
            "VALUES(902,'PROBE-ISBN-902','探针书2','探针',1,902,1,1,0);"
            "UPDATE book SET deleted=1 WHERE id=902;"
            "UPDATE publisher SET deleted=1 WHERE id=902;")
    # C: 已删读者的历史流水（已还、无未缴罚金）→ R10b 不得报
    injC = ("INSERT INTO sys_user(id,username,password,real_name,role,reader_no,borrowed_count) "
            "VALUES(903,'probe903','x','探针读者','READER','RPROBE903',0);"
            "INSERT INTO borrow_record(id,user_id,book_id,borrow_date,due_date,return_date,status,renew_count) "
            "VALUES(903,903,2,DATE_SUB(CURDATE(),INTERVAL 60 DAY),DATE_SUB(CURDATE(),INTERVAL 30 DAY),DATE_SUB(CURDATE(),INTERVAL 40 DAY),2,0);"
            "UPDATE sys_user SET deleted=1 WHERE id=903;")
    # D: 已删图书的历史流水（已还）→ R10a 不得报
    injD = ("INSERT INTO book(id,isbn,title,author,category_id,publisher_id,total_stock,stock,borrow_count) "
            "VALUES(904,'PROBE-ISBN-904','探针书3','探针',1,1,1,1,0);"
            "INSERT INTO borrow_record(id,user_id,book_id,borrow_date,due_date,return_date,status,renew_count) "
            "VALUES(904,3,904,DATE_SUB(CURDATE(),INTERVAL 60 DAY),DATE_SUB(CURDATE(),INTERVAL 30 DAY),DATE_SUB(CURDATE(),INTERVAL 40 DAY),2,0);"
            "UPDATE book SET deleted=1 WHERE id=904;")
    fps = [
      ("A 已删书→已删分类", injA, C_R10c),
      ("B 已删书→已删出版社", injB, C_R10d),
      ("C 已删读者的已还流水", injC, C_R10b),
      ("D 已删图书的已还流水", injD, C_R10a),
    ]
    ok2 = 0
    for name, inj, chk in fps:
        v = probe_once(inj, chk)
        good = v == "0"
        ok2 += 1 if good else 0
        print("  %-22s 误报行数 = %-6s %s" % (name, v, "✓" if good else "✗ 误报!"))

    print("\n" + "=" * 72)
    print("C. 预排除证据：若纳入'续借一致性'规则会怎样")
    print("=" * 72)
    v = probe_once("SELECT 1;", "(SELECT COUNT(*) FROM borrow_record WHERE renew_count=1 AND due_date<>DATE_ADD(borrow_date, INTERVAL 60 DAY))")
    print("  续借一致性规则在种子库的误报行数 =", v, "（证明预排除必要）")

    print("\n" + "=" * 72)
    print(f"结论：防漏报 {ok}/14 通过；防误报 {ok2}/4 通过")
    print("=" * 72)
finally:
    run(f"DROP DATABASE IF EXISTS {PROBE};")
    if os.path.exists(tmp): os.remove(tmp)
    print("[清理] 临时库与临时文件已移除")