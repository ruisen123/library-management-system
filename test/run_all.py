# -*- coding: utf-8 -*-
"""LibMS 统一审查运行器（run_all）——单一入口跑完全部测试套件

用法：
    python test/run_all.py                    # 全部后端套件（Python 脚本）
    python test/run_all.py --browser          # 追加浏览器套件（需 5173 前端 + node + playwright）
    python test/run_all.py --suite e2e_full_flow   # 只跑单个套件
    python test/run_all.py --list             # 列出套件清单

行为：
    1. 预检（mysql / 后端 8080 / 可选前端 5173）；不满足时给出明确指引并退出码 2
    2. 每个套件运行前自动重置种子（DROP+CREATE+导入 docs/04）——不再依赖人工纪律
    3. 逐套件执行、解析"通过/失败"计数、记录耗时与尾部输出（含全部 [FAIL]/[SKIP] 行）
    4. 汇总打印 + 报告落盘 test/artifacts/audit_YYYYMMDD_HHMMSS.md（报告头含代码指纹）
    5. 任何套件失败（含主口径或专项）→ 退出码 1；全绿 → 0；环境缺失 → 2
    6. 判定 = 退出码 0 且 通过数可解析 且 通过数 == 期望数，任一不符即 FAIL
       （2026-10-24 加固：此前期望数不符只写备注仍算 PASS，断言被静默跳过时门禁照样绿）
    7. 专项套件（fix_verify_20261023 / browser_fix_ui）单列执行与统计，
       不计入 19 套件 585 项主口径（对外口径不变）

设计原则：纯标准库、无 shell 管道依赖（本机 coreutils 可能缺失）；
适配本项目的 MySQL 8 / python / node 安装位置（见 _common 候选表）。
"""
import argparse
import datetime
import hashlib
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _common as C  # noqa: E402

# (套件名, 脚本相对路径, 脚本类型, 期望断言数)  —— 期望数是硬判据：
# PASS = 退出码 0 且 通过数可解析 且 通过数 == 期望数（2026-10-24 加固，见文件头 §行为 6）
BACKEND_SUITES = [
    ("e2e_full_flow", "test/e2e_full_flow.py", "py", 119),
    ("smoke_test", "test/smoke_test.py", "py", 55),
    ("smoke_test2", "test/smoke_test2.py", "py", 33),
    ("smoke_test3", "test/smoke_test3.py", "py", 5),
    ("api_test", "test/api_test.py", "py", 31),
    ("boundary_test", "test/boundary_test.py", "py", 22),
    ("security_test", "test/security_test.py", "py", 25),
    ("pagination_test", "test/pagination_test.py", "py", 9),
    ("audit_test", "test/audit_test.py", "py", 29),
    # 创新点②③专项（生成列复用 / 密码指纹令牌失效）：2026-10-19 批T 注册进基线，
    # 理由——不注册的话回归不会自动跑它，创新点②③的证据会随时间失效
    # 2026-10-21 权限模型调整：新增「管理员可建图书」断言，期望数 25→26
    ("innovation_test", "test/innovation_test.py", "py", 26),
    # 2026-10-22 刚需扩展：预约排队 / Excel 导入导出 / 自助注册（注册限流放最后）
    # 2026-10-22 修复预约死锁与并发冲突：新增 3.3b（占位仍可排队）、7.1/7.2（并发预约），期望数 29→32
    ("reservation_test", "test/reservation_test.py", "py", 32),
    ("import_export_test", "test/import_export_test.py", "py", 30),
    # 2026-10-23 推荐项：热门借阅排行 + 读者额度管理（本套件结尾会自行恢复种子）
    ("recommended_test", "test/recommended_test.py", "py", 40),
    # 2026-10-23 推荐项：站内通知（扫描/事件/已读/越权/分页；结尾恢复种子）
    ("notification_test", "test/notification_test.py", "py", 36),
    # 注意：本套件会占满进程内注册限流窗口，必须排在全部后端套件最后；
    # 重复跑全量前请重启后端（限流计数在内存中），否则 1.1 会被上一轮额度拦截。
    ("extension_test", "test/extension_test.py", "py", 19),
]
BROWSER_SUITES = [
    ("browser_e2e", "test/browser/check_final.js", "js", 25),
    ("browser_refresh", "test/browser/refresh_fix.js", "js", 8),
    # 2026-10-22 刚需扩展界面：预约页签、报表导出下载、导入弹窗与模板下载
    # 2026-10-22 预约/取消同一按钮红蓝切换：新增 R1b~R1f 5 条，期望数 12→17
    # 2026-10-22 助理浮动按钮改位（右下角压分页器）：新增 U1~U4 几何断言（滚到底测），期望数 17→21
    ("browser_extension", "test/browser/extension_ui.js", "js", 21),
    # 2026-10-23 推荐项界面：热门借阅条 + 读者额度 + 站内通知（铃铛/消息中心/已读）
    ("browser_recommended", "test/browser/recommended_ui.js", "js", 20),
]
# 专项套件（2026-10-24 D2-04 注册）：照跑、单列、但不计入 19 套件 585 项主口径
# （与 test/mail/run_mail_test.py 独立执行同一思路；浏览器专项跟随 --browser 约定）。
# 理由：10-23 修复的 4 类关键行为（重复在借 / 锁定放行+LOGIN_LOCKED / 库存增加顺延 /
# 统计占比基准）在 585 项内零保护，对应断言原本只在未注册的这两个脚本里。
SPECIAL_SUITES = [
    ("fix_verify_20261023", "test/fix_verify_20261023.py", "py", 35),
    ("browser_fix_ui", "test/browser/fix_ui_20261023.js", "js", 9),
]

SUITE_TIMEOUT = 300  # 单套件上限（秒）


def _file_sha256(path):
    """文件 sha256（完整十六进制）；文件不存在返回 "缺失"。"""
    if not os.path.isfile(path):
        return "缺失"
    h = hashlib.sha256()
    try:
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                h.update(chunk)
        return h.hexdigest()
    except Exception as e:
        return "读取失败(" + str(e)[:40] + ")"


def code_fingerprint(root):
    """(jar sha256, 种子 SQL sha256)——报告头自证主口径 585 项对应哪一版代码（D2-14）。"""
    return (_file_sha256(os.path.join(root, "backend", "target", "libms-backend-1.0.0.jar")),
            _file_sha256(os.path.join(root, "docs", "04_数据库设计.sql")))


def _dir_sha256(root_dir, extra_files=()):
    """目录树指纹：按相对路径排序累加（路径 + 内容）。用于前端源码这类「改一个 .vue 就该让旧报告失效」的场景（R3-P1-06）。"""
    h = hashlib.sha256()
    files = []
    for dirpath, _dirs, names in os.walk(root_dir):
        for n in names:
            files.append(os.path.join(dirpath, n))
    files += [q for q in extra_files if os.path.exists(q)]
    for q in sorted(files):
        h.update(os.path.relpath(q, root_dir).replace(os.sep, "/").encode("utf-8"))
        h.update(b"\x00")
        try:
            with open(q, "rb") as fh:
                for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                    h.update(chunk)
        except Exception:
            h.update(b"<unreadable>")
        h.update(b"\x01")
    return h.hexdigest()


def frontend_fingerprint(root):
    """(前端源码树 sha256, node 版本)——74 项浏览器断言的被测对象只有前端，只记 jar/docs 会让「改一个 .vue 后旧报告仍可冒充本版前端的证据」（R3-P1-06）。"""
    fe = os.path.join(root, "frontend")
    tree = _dir_sha256(os.path.join(fe, "src"),
                       [os.path.join(fe, "package-lock.json"), os.path.join(fe, "vite.config.js")])
    try:
        r = subprocess.run(["node", "-v"], capture_output=True, text=True, timeout=20)
        nv = (r.stdout or "").strip() or "N/A"
    except Exception as e:
        nv = "N/A（" + str(e)[:30] + "）"
    return tree, nv


def jdk_version():
    """JDK 版本（java -version 的 version 字段）；取不到返回 N/A 说明。"""
    try:
        r = subprocess.run(["java", "-version"], capture_output=True, text=True, timeout=20)
        txt = (r.stderr or "") + (r.stdout or "")
        m = re.search(r'version "([^"]+)"', txt)
        if m:
            return m.group(1)
        lines = txt.strip().splitlines()
        return lines[0][:60] if lines else "N/A"
    except Exception as e:
        return "N/A（" + str(e)[:40] + "）"


def parse_counts(suite_name, out, code):
    """从输出解析 (通过数, 失败数)；无法解析返回 (None, None)。
    判定见 run_suite：退出码 0 且 通过数可解析 且 通过数 == 注册期望数（2026-10-24 加固）。"""
    if suite_name.startswith("browser"):
        m = re.search(r"(\d+)/(\d+)\s*====", out)  # "===== 浏览器 E2E 汇总: 20/20 ====="
        if m:
            p, t = int(m.group(1)), int(m.group(2))
            return p, t - p
        return None, None
    p = re.search(r"通过\s*(\d+)\s*(?:项|条)", out)
    f = re.search(r"失败\s*(\d+)\s*(?:项|条)", out)
    return (int(p.group(1)) if p else None, int(f.group(1)) if f else None)


def run_suite(name, rel_path, kind, expect, root, need_reset=True):
    """执行单个套件，返回记录 dict。"""
    script = os.path.join(root, rel_path.replace("/", os.sep))
    rec = {"name": name, "path": rel_path, "expect": expect, "ok": False,
           "passed": None, "failed": None, "code": None, "seconds": 0.0, "tail": "",
           "note": "", "skips": 0, "special": False}
    if not os.path.isfile(script):
        rec["note"] = "脚本不存在"
        return rec
    if need_reset:
        ok, msg = C.reset_seed()
        if not ok:
            rec["note"] = f"种子重置失败：{msg}"
            return rec
        # 2026-10-24（D2-15）：重置后立即按 _common.BASELINE（10 表口径，含 notification）
        # 核对一次——DROP 失败/导入不全时不再把脏数据带进本轮而套件仍报绿
        bad = C.baseline_ok()
        if bad is None or bad:
            rec["note"] = ("重置后基线不可核验" if bad is None else "重置后基线不符：" + " ".join(bad))
            return rec
    if kind == "py":
        cmd = [sys.executable, script]
    else:
        node = C.node_exe()
        nm = C.playwright_nm()
        if node is None or nm is None:
            rec["note"] = "node 或 playwright 依赖不可用"
            return rec
        cmd = [node, script]
    env = dict(os.environ)
    env["NO_PROXY"] = "localhost,127.0.0.1"
    env["no_proxy"] = "localhost,127.0.0.1"
    if kind == "js":
        env["NODE_PATH"] = C.playwright_nm() or ""
    t0 = time.time()
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                           errors="replace", timeout=SUITE_TIMEOUT, cwd=root, env=env)
        rec["code"] = r.returncode
        out = (r.stdout or "") + "\n" + (r.stderr or "")
    except subprocess.TimeoutExpired as e:
        rec["code"] = "TIMEOUT"
        # 超时前的部分输出必须保留（2026-10-24 R3-D2）：此前 out 被清空，
        # 报告里只剩一句"超时"，既无失败明细也无尾行，等于排查无门。
        # capture_output+text 时 e.stdout 为 str；若为 bytes 一并兼容。
        def _as_text(v):
            if v is None:
                return ""
            return v if isinstance(v, str) else v.decode("utf-8", "replace")
        out = _as_text(e.stdout) + "\n" + _as_text(e.stderr)
        rec["note"] = f"超时（>{SUITE_TIMEOUT}s）" + ("" if out.strip() else "，且超时前无任何输出")
    except Exception as e:
        rec["code"] = "ERROR"
        out = ""
        rec["note"] = f"执行异常：{e}"
    rec["seconds"] = round(time.time() - t0, 1)
    rec["passed"], rec["failed"] = parse_counts(name, out, rec["code"])
    rec["ok"] = (rec["code"] == 0)
    non_empty = [ln for ln in out.splitlines() if ln.strip()]
    tail_lines = non_empty[-4:]
    # 失败明细必须留档：浏览器套件把 [FAIL] 打在输出中段，只存尾 4 行会导致
    # "24/25 但查不到是哪条断言失败"（2026-10-22 16:28 那轮实际踩过）
    # 2026-10-24（D2-02）：补 "| FAIL |" —— api_test.py:230 的失败行正是该格式，
    # 不含 [FAIL]、也不以 ✗ 开头，此前 31 项套件的失败明细整段丢失
    fail_lines = [ln for ln in non_empty
                  if "[FAIL]" in ln or "| FAIL |" in ln or ln.lstrip().startswith("✗")
                  or "[BASELINE FAIL]" in ln or ln.lstrip().startswith("FATAL")
                  or ln.lstrip().startswith("Traceback")]
    # 2026-10-24（D2-01）：SKIP/守卫分支显式列出并计数，不再静默缩量
    skip_lines = [ln for ln in non_empty if "[SKIP]" in ln]
    rec["skips"] = len(skip_lines)
    # 同一行可能同时含 [FAIL] 与 [SKIP]，直接相加会重复计数（2026-10-24 R3-D2）；去重且保持先后顺序
    marks = list(dict.fromkeys(fail_lines + skip_lines))
    if marks:
        rec["tail"] = "\n".join(marks + ["--- （以下为输出结尾） "] + tail_lines)
        if fail_lines and not rec["ok"]:
            rec["note"] = f"失败断言 {len(fail_lines)} 条，明细见本报告该套件段"
        if skip_lines:
            rec["note"] = (rec["note"] + "；" if rec["note"] else "") + f"跳过分支 {len(skip_lines)} 处"
    else:
        rec["tail"] = "\n".join(tail_lines)
    # 2026-10-24（D2-01）：判定 = 退出码 0 且 通过数可解析 且 通过数 == 期望数。
    # 此前"期望不符"只写一句备注、仍算 PASS → 断言被条件分支静默跳过时门禁照样绿。
    if rec["ok"] and expect:
        if rec["passed"] is None:
            rec["ok"] = False
            rec["note"] = (rec["note"] + "；" if rec["note"] else "") + \
                "通过数无法从输出解析，无法与期望数比对"
        elif rec["passed"] != expect:
            rec["ok"] = False
            rec["note"] = (rec["note"] + "；" if rec["note"] else "") + \
                f"通过数 {rec['passed']} ≠ 期望 {expect}（有断言被跳过或脚本被改动）"
    # 2026-10-24（D2-17）：api_test 的墙钟性能样本单列（功能断言 / 性能样本 分离显示）
    m_split = re.search(r"其中：功能断言\s*(\d+)\s*项\s*/\s*性能样本\s*(\d+)\s*项", out)
    if m_split:
        rec["note"] = (rec["note"] + "；" if rec["note"] else "") + \
            f"功能断言 {m_split.group(1)} / 性能样本 {m_split.group(2)}"
    return rec


def main():
    ap = argparse.ArgumentParser(description="LibMS 统一审查运行器")
    ap.add_argument("--browser", action="store_true", help="追加浏览器套件（需前端 5173）")
    ap.add_argument("--suite", help="只运行指定套件")
    ap.add_argument("--list", action="store_true", help="列出套件清单")
    args = ap.parse_args()

    root = C.PROJECT_ROOT
    print("=" * 78)
    print("LibMS 统一审查运行器（test/run_all.py）")
    print("=" * 78)

    if args.list:
        print("\n后端套件：")
        for n, p, k, e in BACKEND_SUITES:
            print(f"  - {n:<16} {p}  （期望 {e} 项）")
        print("浏览器套件（--browser）：")
        for n, p, k, e in BROWSER_SUITES:
            print(f"  - {n:<16} {p}  （期望 {e} 项）")
        print("专项套件（单列，不计入 585 项主口径；browser_fix_ui 需 --browser）：")
        for n, p, k, e in SPECIAL_SUITES:
            print(f"  - {n:<16} {p}  （期望 {e} 项）")
        return 0

    # ---------- 预检 ----------
    problems = []
    if C.mysql_exe() is None:
        problems.append("未找到 mysql 客户端（候选：mysql / C:\\Program Files\\MySQL\\...\\mysql.exe）")
    if not C.backend_ready():
        problems.append("后端 8080 未就绪——请先启动：java -jar backend/target/libms-backend-1.0.0.jar --server.port=8080"
                        "（环境变量 DB_USERNAME/DB_PASSWORD/JWT_SECRET 见《从零开始运行手册》）")
    if args.browser and not C.frontend_ready():
        problems.append("前端 5173 未就绪（--browser 需要）——请先启动前端 dev server")
    if problems:
        print("\n[预检未通过]")
        for p in problems:
            print("  ✗ " + p)
        return 2
    print(f"\n[预检通过] mysql ✓ 后端 8080 ✓" + (" 前端 5173 ✓" if args.browser else ""))

    # ---------- 套件选择 ----------
    suites = list(BACKEND_SUITES)
    if args.browser:
        suites += BROWSER_SUITES
    specials = [s for s in SPECIAL_SUITES if s[2] == "py" or args.browser]
    if args.suite:
        suites = [s for s in suites if s[0] == args.suite]
        specials = [s for s in specials if s[0] == args.suite]
        if not suites and not specials:
            print(f"[错误] 未找到套件：{args.suite}（用 --list 查看清单）")
            return 2

    # ---------- 执行 ----------
    records = []
    for name, path, kind, expect in suites:
        print(f"\n---- 运行 {name}（{path}）----")
        rec = run_suite(name, path, kind, expect, root)
        records.append(rec)
        state = "PASS" if rec["ok"] else "FAIL"
        counts = f"{rec['passed']} 项" if rec["passed"] is not None else "未知"
        extra = f"  [{rec['note']}]" if rec["note"] else ""
        print(f"  [{state}] {name}: {counts}，{rec['seconds']}s，退出码={rec['code']}{extra}")
        if rec["tail"]:
            for ln in rec["tail"].splitlines():
                print("    | " + ln)

    # ---------- 专项套件（单列执行；不计入 19 套件 585 项主口径） ----------
    special_records = []
    if specials:
        print("\n" + "-" * 78)
        print("专项套件（不计入 19 套件 585 项主口径）")
        print("-" * 78)
    for name, path, kind, expect in specials:
        print(f"\n---- 运行【专项】{name}（{path}）----")
        rec = run_suite(name, path, kind, expect, root)
        rec["special"] = True
        special_records.append(rec)
        state = "PASS" if rec["ok"] else "FAIL"
        counts = f"{rec['passed']} 项" if rec["passed"] is not None else "未知"
        extra = f"  [{rec['note']}]" if rec["note"] else ""
        print(f"  [{state}] {name}: {counts}，{rec['seconds']}s，退出码={rec['code']}{extra}")
        if rec["tail"]:
            for ln in rec["tail"].splitlines():
                print("    | " + ln)

    # ---------- 收尾：恢复种子 ----------
    # 此前只在每个套件运行前重置；browser_recommended 等套件会改额度/生成通知，
    # 跑完全量后若不重置，交付库会留脏数据。这里统一做最后一次重置并纳入总判定。
    final_reset_ok, final_reset_msg = C.reset_seed()
    if final_reset_ok:
        final_bad = C.baseline_ok()
        if final_bad is None or final_bad:
            final_reset_ok = False
            final_reset_msg = "重置后基线不可核验" if final_bad is None else "重置后基线不符：" + " ".join(final_bad)
    print("\n[收尾] 种子重置：" + ("OK" if final_reset_ok else "FAIL " + final_reset_msg))

    # ---------- 汇总 ----------
    total_pass = sum(r["passed"] for r in records if r["passed"] is not None)
    n_ok = sum(1 for r in records if r["ok"])
    n_all = len(records)
    sp_pass = sum(r["passed"] for r in special_records if r["passed"] is not None)
    sp_ok = sum(1 for r in special_records if r["ok"])
    sp_all = len(special_records)
    all_green = (n_ok == n_all) and (sp_ok == sp_all) and final_reset_ok

    print("\n" + "=" * 78)
    print(f"审查汇总：{n_ok}/{n_all} 套件通过，断言合计 {total_pass} 项")
    for r in records:
        mark = "✓" if r["ok"] else "✗"
        counts = f"{r['passed']} 项" if r["passed"] is not None else "-"
        print(f"  {mark} {r['name']:<16} {counts:>8}  {r['seconds']:>6}s  退出码={r['code']}"
              + (f"  {r['note']}" if r["note"] else ""))
    if special_records:
        print(f"  专项（不计入 {n_all} 套件 {total_pass} 项口径）：{sp_ok}/{sp_all} 通过，断言合计 {sp_pass} 项")
        for r in special_records:
            mark = "✓" if r["ok"] else "✗"
            counts = f"{r['passed']} 项" if r["passed"] is not None else "-"
            print(f"  {mark} [专项] {r['name']:<16} {counts:>8}  {r['seconds']:>6}s  退出码={r['code']}"
                  + (f"  {r['note']}" if r["note"] else ""))
    skip_total_console = sum(r.get("skips", 0) for r in records + special_records)
    if skip_total_console:
        print(f"  ⚠ 本轮共出现 {skip_total_console} 处 [SKIP] 分支（已在各套件段显式列出；"
              f"跳过会导致通过数 ≠ 期望数而判 FAIL）")
    print("=" * 78)

    # ---------- 报告落盘 ----------
    os.makedirs(C.ARTIFACTS_DIR, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    report = os.path.join(C.ARTIFACTS_DIR, f"audit_{stamp}.md")
    try:
        with open(report, "w", encoding="utf-8") as f:
            f.write(f"# LibMS 审查报告（{stamp}）\n\n")
            f.write(f"- 套件结果：**{n_ok}/{n_all} 通过**；断言合计 **{total_pass} 项**\n")
            f.write(f"- 浏览器套件：{'已包含' if args.browser else '未包含（--browser）'}\n")
            # 2026-10-24（D2-14）：报告头自证主口径跑的是哪一版代码（仓库无 .git，只能靠指纹）
            jar_sha, seed_sha = code_fingerprint(root)
            fe_sha, node_ver = frontend_fingerprint(root)
            f.write(f"- 代码指纹：`backend/target/libms-backend-1.0.0.jar` sha256={jar_sha}；"
                    f"`docs/04_数据库设计.sql` sha256={seed_sha}；JDK {jdk_version()}\n")
            # 2026-10-24（R3-P1-06）：74 项浏览器断言的被测对象是前端，只记 jar/docs 会让旧报告冒充本版前端证据
            f.write(f"- 前端指纹：`frontend/src`+`package-lock.json`+`vite.config.js` 树 sha256={fe_sha}；Node {node_ver}\n")
            if special_records:
                sp_names = " + ".join(r["name"] for r in special_records)
                f.write(f"- 专项套件（**不计入上述 {n_all} 套件 {total_pass} 项口径**）："
                        f"**{sp_ok}/{sp_all} 通过**，断言合计 **{sp_pass} 项**（{sp_names}）\n")
            skip_total = sum(r.get("skips", 0) for r in records + special_records)
            f.write(f"- 跳过分支 [SKIP]：**{skip_total} 处**（明细见各套件段；通过数 ≠ 期望数即判 FAIL）\n")
            f.write(f"- 收尾种子重置：**{'OK' if final_reset_ok else 'FAIL'}**{'' if final_reset_ok else '（' + final_reset_msg + '）'}\n\n")
            f.write("| 套件 | 通过 | 耗时 | 退出码 | 结果 | 备注 |\n|---|---|---|---|---|---|\n")
            for r in records:
                f.write(f"| {r['name']} | {r['passed'] if r['passed'] is not None else '-'} | {r['seconds']}s "
                        f"| {r['code']} | {'PASS' if r['ok'] else 'FAIL'} | {r['note']} |\n")
            if special_records:
                f.write("\n### 专项套件（单列；不计入 585 项口径）\n\n")
                f.write("| 套件 | 通过 | 耗时 | 退出码 | 结果 | 备注 |\n|---|---|---|---|---|---|\n")
                for r in special_records:
                    f.write(f"| [专项] {r['name']} | {r['passed'] if r['passed'] is not None else '-'} | {r['seconds']}s "
                            f"| {r['code']} | {'PASS' if r['ok'] else 'FAIL'} | {r['note']} |\n")
            f.write("\n## 各套件尾部输出\n\n")
            for r in records:
                f.write(f"### {r['name']}\n\n```\n{r['tail']}\n```\n\n")
            for r in special_records:
                f.write(f"### [专项] {r['name']}\n\n```\n{r['tail']}\n```\n\n")
        print(f"报告已落盘：{report}")
    except Exception as e:
        print(f"[警告] 报告落盘失败：{e}")

    return 0 if all_green else 1


if __name__ == "__main__":
    sys.exit(main())
