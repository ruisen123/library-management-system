
# -*- coding: utf-8 -*-
import sys, os, subprocess, time, uuid, json
sys.stdout.reconfigure(encoding='utf-8')

MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.EXE'
DB = '_probe_iso_' + uuid.uuid4().hex[:8]
PW = '123456'

def run(sql, db=None, timeout=40):
    cmd = [MYSQL, '-uroot', '-p' + PW, '--batch', '--raw', '-N']
    if db: cmd.append(db)
    cmd += ['-e', sql]
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return r.returncode, (r.stdout or b'').decode('utf-8', 'replace').strip()

run('CREATE DATABASE %s CHARACTER SET utf8mb4' % DB)
run('CREATE TABLE t(id INT PRIMARY KEY, v INT) ENGINE=InnoDB', db=DB)
run('INSERT INTO t VALUES(1,1)', db=DB)

record = {'db': DB, 'mysql': '8.0', 'runs': []}
for level in ('REPEATABLE READ', 'READ COMMITTED'):
    run('UPDATE t SET v=1 WHERE id=1', db=DB)
    # 把 SLEEP 的结果单独区分：用 SLEEP(4) 但包在子查询里不返回行
    sql = ('SET SESSION TRANSACTION ISOLATION LEVEL %s; '
           'START TRANSACTION; '
           'SELECT CONCAT("t0=", v) FROM t WHERE id=1; '
           'DO SLEEP(4); '
           'SELECT CONCAT("t1=", v) FROM t WHERE id=1; '
           'COMMIT;') % level
    cmd = [MYSQL, '-uroot', '-p' + PW, '--batch', '--raw', '-N', DB, '-e', sql]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.5)
    run('UPDATE t SET v=2 WHERE id=1', db=DB)
    outside = run('SELECT v FROM t WHERE id=1', db=DB)[1]
    out, err = p.communicate(timeout=40)
    lines = [x.strip() for x in out.decode('utf-8', 'replace').splitlines() if x.strip().startswith(('t0=','t1='))]
    record['runs'].append({'level': level, 'reads': lines, 'outside_after_commit': outside,
                           'err': (err.decode('utf-8','replace')[:200] if b'ERROR' in err else '')})
    print('\n=== %s ===' % level)
    print('  事务内读取: %s' % lines)
    print('  另一会话提交后外部读取: %s' % outside)

run('DROP DATABASE %s' % DB)
resid = run("SHOW DATABASES LIKE '_probe_iso_%'")[1]
record['residual_dbs'] = resid
print('\n临时库已删除，残留检查: %r' % resid)

# 判定
for r in record['runs']:
    t0 = [x for x in r['reads'] if x.startswith('t0=')]
    t1 = [x for x in r['reads'] if x.startswith('t1=')]
    same = (len(t0) == 1 and len(t1) == 1 and t0[0][3:] == t1[0][3:])
    r['same_within_tx'] = same
    print('  %-16s %s -> %s' % (r['level'], r['reads'], '两次相同（快照读）' if same else '两次不同（读到新提交）'))

out = r"C:\Users\23754\dsh-probe\out\isolation_test_result.json"
with open(out, 'w', encoding='utf-8') as f:
    json.dump(record, f, ensure_ascii=False, indent=1)
print('\n结果已存: %s' % out)
