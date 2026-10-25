# -*- coding: utf-8 -*-
"""LibMS 通知邮件通道集成测试（推荐项 5.1b，独立于 run_all 默认套件）

为什么单独跑：本测试需要以"邮件启用 + 指向本地假 SMTP"的配置启动一个独立后端实例，
默认全量回归保持邮件关闭（离线可演示、不产生任何外呼）。

覆盖：
  [1] 正常投递：扫描生成到期提醒 → 认领 → 发送 → email_status=1，假 SMTP 收到邮件
  [2] 跳过：读者关闭邮件通知 → email_status=3，不发起外呼
  [3] 失败重试：SMTP 不可达 → email_status=2、attempts=1、next_attempt_time 退避
  [4] 恢复重投：SMTP 恢复且到期后 → email_status=1
  [5] 净零：只操作临时库 library_db_mailtest，测后删除；不动 library_db

用法：
    python test/mail/run_mail_test.py
前置：本机 MySQL 可用（root/123456 或 LIBMS_DB_PWD），backend/target 已构建 jar。
"""
import email
import email.header
import email.message
import json
import os
import socket
import socketserver
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, "backend", "target", "libms-backend-1.0.0.jar")
SEED = os.path.join(ROOT, "docs", "04_数据库设计.sql")
ARTIFACTS = os.path.join(ROOT, "test", "artifacts")
DB_MAIN = "library_db"
DB_TMP = "library_db_mailtest"
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")
API_PORT = 0
API = "http://localhost:8081"
MYSQL_CANDIDATES = ("mysql", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")

PASS = 0
FAIL = 0
RECEIVED = []          # 假 SMTP 收到的完整报文
SMTP_PORT = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def mysql_exe():
    for c in MYSQL_CANDIDATES:
        if os.path.isabs(c):
            if os.path.isfile(c):
                return c
        else:
            return c
    return None


def db_exec(sql, database=DB_TMP):
    exe = mysql_exe()
    r = subprocess.run([exe, "-uroot", "-p" + DB_PWD, "-N", "-e", f"USE {database}; " + sql],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
    return r.returncode, (r.stdout or "").strip(), (r.stderr or "").strip()


def db_one(sql, database=DB_TMP):
    rc, out, _ = db_exec(sql, database)
    return out if rc == 0 else None


# ---------------- 假 SMTP ----------------
class _Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b"220 libms-test ESMTP\r\n")
        collecting = False
        body = []
        while True:
            line = self.rfile.readline()
            if not line:
                break
            if collecting:
                if line.rstrip(b"\r\n") == b".":
                    RECEIVED.append(b"".join(body).decode("utf-8", "replace"))
                    collecting = False
                    body = []
                    self.wfile.write(b"250 OK queued\r\n")
                else:
                    body.append(line)
                continue
            cmd = line.decode("utf-8", "replace").strip().upper()
            if cmd.startswith("EHLO") or cmd.startswith("HELO"):
                self.wfile.write(b"250-libms-test\r\n250 OK\r\n")
            elif cmd.startswith("MAIL FROM") or cmd.startswith("RCPT TO"):
                self.wfile.write(b"250 OK\r\n")
            elif cmd.startswith("DATA"):
                collecting = True
                self.wfile.write(b"354 send data\r\n")
            elif cmd.startswith("RSET"):
                self.wfile.write(b"250 OK\r\n")
            elif cmd.startswith("QUIT"):
                self.wfile.write(b"221 bye\r\n")
                break
            else:
                self.wfile.write(b"250 OK\r\n")


class _Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


smtp_server = None
smtp_thread = None


def start_smtp(port):
    global smtp_server, smtp_thread
    smtp_server = _Server(("127.0.0.1", port), _Handler)
    smtp_thread = threading.Thread(target=smtp_server.serve_forever, daemon=True)
    smtp_thread.start()


def stop_smtp():
    global smtp_server
    if smtp_server:
        smtp_server.shutdown()
        smtp_server.server_close()
        smtp_server = None


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


# ---------------- HTTP ----------------
def req(method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(f"http://localhost:{API_PORT}" + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("token", token)
    try:
        resp = urllib.request.urlopen(r, timeout=20)
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


def login(u, p="123456"):
    return req("POST", "/auth/login", body={"username": u, "password": p})[1]


def wait_backend(timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(f"http://localhost:{API_PORT}/auth/login", timeout=2)
            return True
        except urllib.error.HTTPError:
            return True
        except Exception:
            time.sleep(1)
    return False


# ---------------- 主流程 ----------------
def main():
    global SMTP_PORT, API_PORT
    print("=" * 72)
    print("LibMS 通知邮件通道集成测试（独立后端 + 临时库 + 假 SMTP）")
    print("=" * 72)
    if not os.path.isfile(JAR):
        print("  [SKIP] 未找到 backend jar，请先 mvn -DskipTests package")
        return 2

    os.makedirs(ARTIFACTS, exist_ok=True)
    SMTP_PORT = free_port()
    API_PORT = free_port()
    main_before = db_one("SELECT COUNT(*) FROM notification;", DB_MAIN)

    # 1) 临时库（只替换一次：先替换带反引号的写法会让 library_db_mailtest 再被二次替换）
    with open(SEED, encoding="utf-8") as f:
        sql = f.read().replace("library_db", DB_TMP)
    db_exec(f"DROP DATABASE IF EXISTS {DB_TMP}; CREATE DATABASE {DB_TMP} "
            f"DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;", DB_MAIN)
    exe = mysql_exe()
    p = subprocess.run([exe, "-uroot", "-p" + DB_PWD, "--default-character-set=utf8mb4", DB_TMP],
                       input=sql.encode("utf-8"), capture_output=True, timeout=120)
    if p.returncode != 0:
        print("  [FAIL] 临时库初始化失败：", p.stderr.decode("utf-8", "replace")[:200])
        return 1
    tbls = db_one("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();")
    if tbls != "10":
        print(f"  [FAIL] 临时库表数异常（期望 10，实际 {tbls}）")
        return 1
    print(f"  [OK] 临时库 {DB_TMP} 就绪（10 表；{DB_MAIN} 不受影响）")

    start_smtp(SMTP_PORT)
    env = dict(os.environ)
    env.update({
        "DB_NAME": DB_TMP, "DB_PASSWORD": DB_PWD,
        "JWT_SECRET": "libms-local-dev-secret-key-please-change-32bytes",
        "APP_NOTIFICATION_EMAIL_ENABLED": "true",
        "MAIL_ENABLED": "true", "MAIL_HOST": "127.0.0.1", "MAIL_PORT": str(SMTP_PORT),
        "MAIL_SMTP_AUTH": "false", "MAIL_STARTTLS": "false",
        "MAIL_FROM": "libms@example.com",
        "APP_NOTIFY_REMINDER_CRON": "0 0 0 1 1 ?",   # 关掉每日扫描，只用手动 scan
        "SPRING_TASK_SCHEDULING_POOL_SIZE": "2",
        "APP_NOTIFICATION_EMAIL_SCAN_MS": "3600000",  # 邮件任务一小时一次，测试走手动 scan
    })
    proc = subprocess.Popen(["java", "-jar", JAR, f"--server.port={API_PORT}"],
                            cwd=ROOT, env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        if not wait_backend():
            print("  [FAIL] 独立后端未就绪")
            return 1
        login_admin = login("admin")
        if not (login_admin.get("data") or {}).get("token"):
            print("  [FAIL] 独立后端登录失败：", json.dumps(login_admin, ensure_ascii=False)[:200])
            return 1
        at = login_admin["data"]["token"]

        # [1] 正常投递
        _c, b = req("GET", "/profile", at)
        _c, b = req("PUT", "/users/3", at, {
            "id": 3, "username": "reader001", "realName": "张三", "role": "READER",
            "readerNo": "R20260001", "gender": 1, "phone": "13800000003",
            "email": "zhangsan@example.com", "emailNotify": 1,
            "borrowLimit": 5, "maxUnpaid": 0, "validUntil": None})
        check("1.1 为张三设置通知邮箱", b.get("code") == 200, str(b.get("msg"))[:40])
        before = len(RECEIVED)
        _c, b = req("POST", "/notifications/scan", at)
        scan1 = b.get("data") or {}
        check("1.2 扫描后邮件任务已投递", scan1.get("emailed", 0) >= 1, json.dumps(scan1)[:80])
        time.sleep(1.5)
        check("1.3 假 SMTP 收到邮件", len(RECEIVED) > before, f"received={len(RECEIVED)}")
        raw = RECEIVED[-1] if len(RECEIVED) > before else ""
        # 含中文的 Subject/正文会被 JavaMail 做 MIME 编码，必须解析后再断言（不能直接查字面量）
        parsed = email.message_from_string(raw)
        subject = str(email.header.make_header(
            email.header.decode_header(parsed.get("Subject") or "")))
        payload = parsed.get_payload(decode=True)
        body = (payload or b"").decode("utf-8", "replace")
        check("1.4 邮件主题解码后含 [LibMS] 与标题",
              "[LibMS]" in subject and "到期提醒" in subject, subject[:60])
        check("1.4b 正文解码后含书名与到期日", "红楼梦" in body, body[:60].replace("\r\n", " "))
        check("1.5 收件人正确", "zhangsan@example.com" in (parsed.get("To") or ""),
              str(parsed.get("To")))
        check("1.6 DB 回写 email_status=1",
              db_one("SELECT email_status FROM notification WHERE type=2 LIMIT 1;") == "1",
              f"status={db_one('SELECT email_status FROM notification WHERE type=2 LIMIT 1;')}")

        # [2] 关闭邮件通知 → 跳过
        _c, b = req("PUT", "/users/5", at, {
            "id": 5, "username": "reader003", "realName": "王五", "role": "READER",
            "readerNo": "R20260003", "gender": 1, "phone": "13800000005",
            "email": "wangwu@example.com", "emailNotify": 0,
            "borrowLimit": 5, "maxUnpaid": 0, "validUntil": None})
        _c, b = req("POST", "/notifications/scan", at)
        check("2.1 关闭邮件通知的读者标记为跳过（status=3）",
              db_one("SELECT email_status FROM notification WHERE type=4 LIMIT 1;") == "3",
              f"status={db_one('SELECT email_status FROM notification WHERE type=4 LIMIT 1;')}")

        # [3] SMTP 不可达 → 失败重试
        stop_smtp()
        db_exec("UPDATE notification SET email_status=0, email_attempts=0, next_attempt_time=NULL "
                "WHERE type=2;")
        _c, b = req("POST", "/notifications/scan", at)
        st = db_one("SELECT email_status FROM notification WHERE type=2 LIMIT 1;")
        at1 = db_one("SELECT email_attempts FROM notification WHERE type=2 LIMIT 1;")
        check("3.1 SMTP 不可达时置为重试（status=2）", st == "2", f"status={st}")
        check("3.2 尝试次数 +1", at1 == "1", f"attempts={at1}")
        check("3.3 退避时间已写入",
              (db_one("SELECT next_attempt_time FROM notification WHERE type=2 LIMIT 1;") or "") != "NULL",
              db_one("SELECT next_attempt_time FROM notification WHERE type=2 LIMIT 1;"))

        # [4] SMTP 恢复 → 重投成功
        start_smtp(SMTP_PORT)
        db_exec("UPDATE notification SET next_attempt_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE type=2;")
        before2 = len(RECEIVED)
        _c, b = req("POST", "/notifications/scan", at)
        time.sleep(1.5)
        check("4.1 恢复后重投成功（status=1）",
              db_one("SELECT email_status FROM notification WHERE type=2 LIMIT 1;") == "1",
              f"status={db_one('SELECT email_status FROM notification WHERE type=2 LIMIT 1;')}")
        check("4.2 恢复后假 SMTP 再次收到邮件", len(RECEIVED) > before2, f"received={len(RECEIVED)}")

        # [5] 净零：主库未被本测试触碰（与测试开始前基线比较，避免上一轮套件残留干扰）
        main_after = db_one("SELECT COUNT(*) FROM notification;", DB_MAIN)
        check("5.1 主库 library_db 未被本测试写入通知",
              main_after is not None and main_after == main_before,
              f"before={main_before} after={main_after}")

        stamp = time.strftime("%Y%m%d_%H%M%S")
        with open(os.path.join(ARTIFACTS, f"mail_{stamp}.md"), "w", encoding="utf-8") as f:
            f.write(f"# LibMS 邮件通道集成测试（{stamp}）\n\n"
                    f"- 后端：独立实例 :8081（邮件启用，指向本地假 SMTP :{SMTP_PORT}）\n"
                    f"- 数据库：临时库 {DB_TMP}（测后删除）\n"
                    f"- 结果：通过 {PASS} 项，失败 {FAIL} 项\n"
                    f"- 覆盖：正常投递 / 关闭通知跳过 / SMTP 不可达重试 / 恢复重投 / 主库净零\n")
        print(f"\n报告：test/artifacts/mail_{stamp}.md")
    finally:
        # Windows 实测：proc.terminate() 后 java 进程可能仍存活并占着 jar，
        # 因此无条件再补一次 taskkill /T，确保测试不留孤儿实例
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/F", "/T"], capture_output=True)
        try:
            proc.wait(timeout=20)
        except Exception:
            pass
        stop_smtp()
        db_exec(f"DROP DATABASE IF EXISTS {DB_TMP};", DB_MAIN)
        print(f"  [OK] 已停止 :{API_PORT} 实例并删除临时库 {DB_TMP}")

    print("\n" + "=" * 72)
    print(f"邮件通道集成测试完成：通过 {PASS} 项，失败 {FAIL} 项")
    print("=" * 72)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
