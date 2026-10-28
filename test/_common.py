# -*- coding: utf-8 -*-
"""LibMS 审查工具共享库（_common）

供 run_all.py 运行器与各测试套件复用的公共能力：
  · 路径与环境探测（mysql / python / node，含本机安装路径回退）
  · 数据库直查（db_one / db_multi）与种子重置（reset_seed）
  · 服务探活（probe）
  · 种子基线常量（BASELINE）

设计原则（对应 test/README.md）：纯标准库；不依赖 shell 管道与 coreutils；
所有外部程序按"命令名 → 本机绝对路径"双候选探测，找不到返回 None 而非崩溃。
"""
import os
import subprocess
import sys
import urllib.request

# ---------- 路径与环境 ----------
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # 仓库根（test/ 的上级）
SEED_SQL = os.path.join(PROJECT_ROOT, "docs", "04_数据库设计.sql")
DB_NAME = "library_db"
DB_USER = "root"
DB_PWD = os.environ.get("LIBMS_DB_PWD", "123456")  # 可用环境变量覆盖（默认本地测试口令，见运行手册）
DB_HOST = os.environ.get("LIBMS_DB_HOST", "").strip()  # 留空=沿用 mysql 客户端默认（本机 socket/localhost）
DB_PORT = os.environ.get("LIBMS_DB_PORT", "").strip()  # 留空=默认 3306；CI 的 service 容器走 TCP 时需显式指定

MYSQL_CANDIDATES = ("mysql", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
NODE_CANDIDATES = (
    r"C:\Users\<你的用户名>\.workbuddy\binaries\node\versions\22.22.2-3\node.exe",
    r"C:\Program Files\nodejs\node.exe",
    "node",
)
# playwright 依赖目录候选（浏览器套件 require('playwright') 经 NODE_PATH 解析）
PLAYWRIGHT_NM_CANDIDATES = (
    os.path.join(PROJECT_ROOT, "test", "browser", "node_modules"),   # 固化位置（playwright@1.62）
    os.path.join(os.environ.get("TEMP", r"C:\Users\<你的用户名>\AppData\Local\Temp"), "pwcheck", "node_modules"),  # 历史路径，仅兜底
)

# 种子基线（docs/04 导入后的 10 表计数；供套件与运行器校验）
# 2026-10-24（D2-15）：补 notification:0——此前缺第 10 张表，10 表口径守不住
BASELINE = {"sys_user": 6, "category": 4, "publisher": 4, "book": 6,
            "borrow_record": 8, "fine_record": 3, "notice": 3, "operation_log": 3,
            "reservation": 0, "notification": 0}

ARTIFACTS_DIR = os.path.join(PROJECT_ROOT, "test", "artifacts")


def _find(candidates):
    """返回第一个可执行候选（绝对路径存在，或裸命令名交给系统解析）；找不到返回 None。"""
    for c in candidates:
        if os.path.isabs(c):
            if os.path.isfile(c):
                return c
        else:
            return c  # 裸命令名：交由 subprocess 解析（Windows 下会走 PATHEXT）
    return None


def mysql_exe():
    return _find(MYSQL_CANDIDATES)


def mysql_args(exe):
    """mysql 客户端的公共参数：账号与口令，以及可选的远程主机/端口。

    默认不带 -h/-P，保持本机原有行为（Windows 走 localhost、Linux 走 socket）；
    在 CI 等场景下通过 LIBMS_DB_HOST / LIBMS_DB_PORT 指定 TCP 地址。
    """
    args = [exe, "-u" + DB_USER, "-p" + DB_PWD]
    if DB_HOST:
        args += ["-h", DB_HOST]
    if DB_PORT:
        args += ["-P", DB_PORT]
    return args


def node_exe():
    return _find(NODE_CANDIDATES)


def playwright_nm():
    for p in PLAYWRIGHT_NM_CANDIDATES:
        if os.path.isdir(os.path.join(p, "playwright")):
            return p
    return None


# ---------- 数据库 ----------
def db_multi(sql):
    """执行 SQL（在 library_db 上）并返回 stdout（去尾空白）；mysql 不可用/失败返回 None。"""
    exe = mysql_exe()
    if exe is None:
        return None
    cmd = mysql_args(exe) + ["-N", "-e", f"USE {DB_NAME}; " + sql]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=20)
        if r.returncode == 0:
            return (r.stdout or "").strip()
    except Exception:
        pass
    return None


def db_one(sql):
    """直查数据库返回单值；mysql 不可用返回 None。"""
    return db_multi(sql)


def reset_seed():
    """重置数据库种子（DROP + CREATE + 导入 docs/04）。返回 (ok, 说明)。"""
    exe = mysql_exe()
    if exe is None:
        return False, "未找到 mysql 客户端"
    if not os.path.isfile(SEED_SQL):
        return False, f"种子文件不存在：{SEED_SQL}"
    try:
        prep = subprocess.run(mysql_args(exe) + ["--default-character-set=utf8mb4",
                               "-e", f"DROP DATABASE IF EXISTS {DB_NAME}; "
                                     f"CREATE DATABASE {DB_NAME} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"],
                              capture_output=True, timeout=60)
        if prep.returncode != 0:
            return False, "重建数据库失败：" + (prep.stderr or b"").decode("utf-8", "replace")[:200]
        with open(SEED_SQL, "rb") as f:
            r = subprocess.run(mysql_args(exe) + ["--default-character-set=utf8mb4", DB_NAME],
                               stdin=f, capture_output=True, timeout=120)
        if r.returncode != 0:
            return False, "种子导入失败（退出码非 0）"
        return True, "种子已重置"
    except Exception as e:
        return False, f"种子重置异常：{e}"


def baseline_ok():
    """校验 10 表计数是否等于种子基线；mysql 不可用返回 None，全部相符返回 []。"""
    bad = []
    for table, want in BASELINE.items():
        got = db_one(f"SELECT COUNT(*) FROM {table};")
        if got is None:
            return None
        if got != str(want):
            bad.append(f"{table}={got}(期望{want})")
    return bad


# ---------- 服务探活 ----------
def probe(url, timeout=3):
    """URL 可响应（含 4xx/5xx）即视为就绪；连接失败返回 False。"""
    try:
        urllib.request.urlopen(url, timeout=timeout)
        return True
    except Exception as e:
        return getattr(e, "code", None) is not None


def backend_ready(timeout=3):
    return probe("http://localhost:8080/auth/login", timeout=timeout)


def frontend_ready(timeout=3):
    return probe("http://127.0.0.1:5173/", timeout=timeout)
