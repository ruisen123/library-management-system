
# -*- coding: utf-8 -*-
"""巡检 14 条规则万级性能实测（test/perf）。

用法：
  set LIBMS_DB_PWD=***            # 必填：测试库口令（不提供默认值）
  python test/perf/bench_audit_rules.py
  python test/perf/bench_audit_rules.py --dry-run

环境变量：
  LIBMS_DB_PWD  必填，测试库口令
  LIBMS_MYSQL   可选，mysql 客户端路径；缺省从 PATH 查找

注意：脚本会创建并 DROP 探针库 library_all14，只应对测试库执行。
R1 采用已验证等价的 JOIN 写法。"""
import os, subprocess, sys, time, shutil
sys.stdout.reconfigure(encoding='utf-8')

def _find_root():
    d = os.path.dirname(os.path.abspath(__file__))
    for _ in range(6):
        if os.path.isfile(os.path.join(d, "docs", "04_数据库设计.sql")):
            return d
        d = os.path.dirname(d)
    raise RuntimeError("no root")

ROOT = _find_root()
SEED = os.path.join(ROOT, "docs", "04_数据库设计.sql")
MYSQL = os.environ.get("LIBMS_MYSQL") or shutil.which("mysql")
PROBE = "library_all14"
PWD_ = os.environ.get("LIBMS_DB_PWD")
TMPD = os.path.join(ROOT, "test", "perf", "_tmp")

def sf(n, c):
    p = os.path.join(TMPD, n); open(p, "w", encoding="utf-8").write(c); return p
def run(path, db=None, timeout=900):
    cmd = [MYSQL, "-uroot", "-p"+PWD_, "--default-character-set=utf8mb4", "-N", "-B"]
    if db: cmd.append(db)
    with open(path, "rb") as f:
        return subprocess.run(cmd, stdin=f, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
def q(sql, db=None, timeout=900): return run(sf("_i.sql", sql), db=db, timeout=timeout)

NB, NBR = 10000, 50000
RULES = [
 ("R1  库存自洽(JOIN改写)", "SELECT COUNT(*) FROM book b LEFT JOIN (SELECT book_id,COUNT(*) c FROM borrow_record WHERE status IN(1,3) GROUP BY book_id) t ON t.book_id=b.id WHERE (b.total_stock-b.stock)<>COALESCE(t.c,0);"),
 ("R2  在借数一致", "SELECT COUNT(*) FROM sys_user u WHERE u.role='READER' AND u.borrowed_count<>(SELECT COUNT(*) FROM borrow_record br WHERE br.user_id=u.id AND br.status=1);"),
 ("R3  在借无归还日", "SELECT COUNT(*) FROM borrow_record WHERE status=1 AND return_date IS NOT NULL;"),
 ("R4  已还有归还日", "SELECT COUNT(*) FROM borrow_record WHERE status=2 AND (return_date IS NULL OR return_date<borrow_date);"),
 ("R5  日期顺序", "SELECT COUNT(*) FROM borrow_record WHERE due_date<borrow_date;"),
 ("R6  罚金归属", "SELECT COUNT(*) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.user_id<>br.user_id;"),
 ("R7  缴纳配套", "SELECT COUNT(*) FROM fine_record WHERE (status=2 AND paid_time IS NULL) OR (status=1 AND paid_time IS NOT NULL);"),
 ("R8  金额可重算", "SELECT COUNT(*) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.type=1 AND br.return_date IS NOT NULL AND f.amount<>ROUND(DATEDIFF(br.return_date,br.due_date)*0.5,2);"),
 ("R9a 赔偿有罚金", "SELECT COUNT(*) FROM borrow_record br WHERE br.status=3 AND NOT EXISTS(SELECT 1 FROM fine_record f WHERE f.borrow_id=br.id AND f.type IN(2,3));"),
 ("R9b 罚金有赔偿", "SELECT COUNT(*) FROM fine_record f JOIN borrow_record br ON br.id=f.borrow_id WHERE f.type IN(2,3) AND br.status<>3;"),
 ("R10a 已删书在借", "SELECT COUNT(*) FROM book b WHERE b.deleted=1 AND EXISTS(SELECT 1 FROM borrow_record br WHERE br.book_id=b.id AND br.status=1);"),
 ("R10b 已删用户", "SELECT COUNT(*) FROM sys_user u WHERE u.deleted=1 AND (EXISTS(SELECT 1 FROM borrow_record br WHERE br.user_id=u.id AND br.status=1) OR EXISTS(SELECT 1 FROM fine_record f WHERE f.user_id=u.id AND f.status=1));"),
 ("R10c 已删分类", "SELECT COUNT(*) FROM category c WHERE c.deleted=1 AND EXISTS(SELECT 1 FROM book b WHERE b.category_id=c.id AND b.deleted=0);"),
 ("R10d 已删出版社", "SELECT COUNT(*) FROM publisher p WHERE p.deleted=1 AND EXISTS(SELECT 1 FROM book b WHERE b.publisher_id=p.id AND b.deleted=0);"),
]

if "--dry-run" in sys.argv:
    print("[DRY-RUN] 探针库=%s；计划灌入 book=%d、borrow=%d，逐条执行 %d 条规则，结束后 DROP 探针库并清理 %s" % (PROBE, NB, NBR, len(RULES), TMPD))
    sys.exit(0)
if not MYSQL:
    print("[SKIP] 未找到 mysql 客户端：请设置 LIBMS_MYSQL 或把 mysql 加入 PATH")
    sys.exit(0)
if not PWD_:
    print("[SKIP] 未设置 LIBMS_DB_PWD（测试库口令）；为避免口令硬编码，脚本不提供默认值")
    sys.exit(0)
os.makedirs(TMPD, exist_ok=True)

try:
    q(f"DROP DATABASE IF EXISTS {PROBE};")
    q(f"CREATE DATABASE {PROBE} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;")
    assert run(sf("seed.sql", open(SEED, encoding="utf-8").read().replace("library_db", PROBE)), db=PROBE).returncode == 0
    q("SET FOREIGN_KEY_CHECKS=0; TRUNCATE borrow_record; TRUNCATE fine_record; DELETE FROM book; SET FOREIGN_KEY_CHECKS=1;", db=PROBE)

    q("SET SESSION cte_max_recursion_depth=200000;\n"
      "INSERT INTO book(id,isbn,title,author,category_id,publisher_id,total_stock,stock,borrow_count)\n"
      "WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<"+str(NB)+")\n"
      "SELECT n, CONCAT('P',LPAD(n,8,'0')), CONCAT('书',n), 'a', (n%4)+1, (n%4)+1, 10, 10, 0 FROM s;", db=PROBE)
    q("SET SESSION cte_max_recursion_depth=200000;\n"
      "INSERT INTO borrow_record(user_id,book_id,borrow_date,due_date,return_date,status,renew_count)\n"
      "WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n<"+str(NBR)+")\n"
      "SELECT 3+(n%4), (n%"+str(NB)+")+1,\n"
      " DATE_SUB(CURDATE(),INTERVAL 30+(n%300) DAY),\n"
      " DATE_ADD(DATE_SUB(CURDATE(),INTERVAL 30+(n%300) DAY),INTERVAL 30 DAY),\n"
      " CASE WHEN n%5<2 THEN NULL ELSE DATE_ADD(DATE_SUB(CURDATE(),INTERVAL 30+(n%300) DAY),INTERVAL 25+(n%20) DAY) END,\n"
      " CASE WHEN n%5<2 THEN 1 WHEN n%5<4 THEN 2 ELSE 3 END, 0 FROM s;", db=PROBE)

    # 校准（JOIN 快路径）
    q("UPDATE book b LEFT JOIN (SELECT book_id,COUNT(*) c FROM borrow_record WHERE status IN(1,3) GROUP BY book_id) t "
      "ON t.book_id=b.id SET b.stock=b.total_stock-COALESCE(t.c,0);", db=PROBE)
    q("UPDATE sys_user u LEFT JOIN (SELECT user_id,COUNT(*) c FROM borrow_record WHERE status=1 GROUP BY user_id) t "
      "ON t.user_id=u.id SET u.borrowed_count=COALESCE(t.c,0) WHERE u.role='READER';", db=PROBE)
    # 罚金数据：逾期(type1) + 赔偿(type3)
    q("INSERT INTO fine_record(user_id,borrow_id,amount,type,status,paid_time) "
      "SELECT br.user_id, br.id, ROUND(DATEDIFF(br.return_date,br.due_date)*0.5,2), 1, IF(br.id%3=0,2,1), IF(br.id%3=0,NOW(),NULL) "
      "FROM borrow_record br WHERE br.status=2 AND br.return_date>br.due_date;", db=PROBE)
    q("INSERT INTO fine_record(user_id,borrow_id,amount,type,status,paid_time) "
      "SELECT br.user_id, br.id, 50.00, 3, 2, NOW() FROM borrow_record br WHERE br.status=3;", db=PROBE)

    print("规模: book=%s borrow=%s fine=%s" % (
        q("SELECT COUNT(*) FROM book;", db=PROBE).stdout.strip(),
        q("SELECT COUNT(*) FROM borrow_record;", db=PROBE).stdout.strip(),
        q("SELECT COUNT(*) FROM fine_record;", db=PROBE).stdout.strip()))

    print("\n%-22s %10s %10s" % ("规则", "最快ms", "异常行数"))
    print("-"*46)
    tot = 0.0
    for label, sql in RULES:
        ts, res = [], ""
        for _ in range(2):
            t0=time.perf_counter()
            r=q("USE "+PROBE+"; SET SESSION max_execution_time=60000; "+sql, db=PROBE, timeout=180)
            ts.append((time.perf_counter()-t0)*1000); res=r.stdout.strip()
        tot += min(ts)
        print("%-22s %10.0f %10s" % (label, min(ts), res or "ERR"))
    print("-"*46)
    print("14 条串行合计 ≈ %.0f ms" % tot)
finally:
    try: q(f"DROP DATABASE IF EXISTS {PROBE};", timeout=300)
    except Exception as e: print("清理告警:", str(e)[:80])
    import shutil; shutil.rmtree(TMPD, ignore_errors=True)
    print("[清理] 完成")