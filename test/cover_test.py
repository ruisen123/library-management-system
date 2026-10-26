# -*- coding: utf-8 -*-
"""封面接口专项（不计入 585 主口径）：
成功上传 / 文件落盘 / 读者 403 / 无令牌 401 / 错误扩展名 400 /
超过 2MB 400 / 空文件 400 / 原始文件名含 ../ 时仍随机化到 upload 内。
"""
import base64
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UPLOAD = os.path.join(ROOT, "upload")
ART = os.path.join(ROOT, "test", "artifacts", "cover_upload_" + time.strftime("%Y%m%d_%H%M%S"))
os.makedirs(ART, exist_ok=True)

PASS = 0
FAIL = 0
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def req(method, path, token=None, body=None, raw=None, ctype="application/json", timeout=30):
    data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", ctype)
    if token:
        r.add_header("token", token)
    try:
        resp = urllib.request.urlopen(r, timeout=timeout)
        text = resp.read().decode("utf-8", "replace")
        try:
            return resp.status, json.loads(text)
        except Exception:
            return resp.status, {"raw": text[:120]}
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"raw": text[:120]}
    except Exception as e:
        return -1, {"msg": "EXC:" + str(e)}


def login(name):
    _c, b = req("POST", "/auth/login", body={"username": name, "password": "123456"})
    return (b.get("data") or {}).get("token")


def multipart(filename, content, field="file"):
    boundary = "----LibMSCover" + str(int(time.time() * 1000))
    body = (
        ("--" + boundary + "\r\n").encode()
        + ('Content-Disposition: form-data; name="%s"; filename="%s"\r\n' % (field, filename)).encode("utf-8")
        + b"Content-Type: image/png\r\n\r\n"
        + content
        + ("\r\n--" + boundary + "--\r\n").encode()
    )
    return body, "multipart/form-data; boundary=" + boundary


def upload(token, filename, content):
    body, ctype = multipart(filename, content)
    return req("POST", "/books/cover", token=token, raw=body, ctype=ctype, timeout=60)


def saved_path(data):
    return str(data or "")


def move_out(rel, prefix):
    if not rel.startswith("/upload/"):
        return None
    name = os.path.basename(rel)
    if not name or name in (".", "..") or "/" in name or "\\" in name:
        return None
    src = os.path.join(UPLOAD, name)
    if not os.path.isfile(src):
        return None
    dst = os.path.join(ART, prefix + "_" + name)
    shutil.move(src, dst)
    return dst


at = login("admin")
lt = login("librarian01")
rt = login("reader001")
check("管理员/馆员/读者登录取得令牌", bool(at and lt and rt))

# 1) 成功上传 + 路径格式
_c, b = upload(lt, "cover.png", PNG)
path = saved_path((b.get("data") if isinstance(b, dict) else None))
check("馆员上传 PNG 成功且返回 /upload/cover-*.png",
      b.get("code") == 200 and path.startswith("/upload/cover-") and path.endswith(".png"), str(b)[:160])

# 2) 文件真实落盘且内容一致，移入 artifacts
moved = move_out(path, "success")
ok_content = False
if moved:
    with open(moved, "rb") as f:
        ok_content = f.read() == PNG
check("上传文件真实落盘且内容一致", bool(moved) and ok_content, f"moved={moved}")

# 3) 读者 403
_c, b = upload(rt, "cover.png", PNG)
check("读者上传被拒（403）", b.get("code") == 403, str(b)[:120])

# 4) 无令牌 401
_c, b = upload(None, "cover.png", PNG)
check("无令牌上传被拒（401）", b.get("code") == 401, str(b)[:120])

# 5) 错误扩展名 400
_c, b = upload(lt, "cover.txt", PNG)
check("错误扩展名被拒（400）", b.get("code") == 400 and "jpg" in str(b.get("msg", "")), str(b)[:160])

# 6) 超过 2MB 400
_c, b = upload(lt, "big.png", b"x" * (2 * 1024 * 1024 + 1))
check("超过 2MB 被拒（400）", b.get("code") == 400 and "2MB" in str(b.get("msg", "")), str(b)[:160])

# 7) 空文件 400
_c, b = upload(lt, "empty.png", b"")
check("空文件被拒（400）", b.get("code") == 400, str(b)[:160])

# 8) 原始文件名 ../evil.png：仍随机化到 upload 内
_c, b = upload(lt, "../evil.png", PNG)
path2 = saved_path((b.get("data") if isinstance(b, dict) else None))
moved2 = move_out(path2, "traversal")
check("原始文件名含 ../ 仍随机化到 upload 内", b.get("code") == 200 and path2.startswith("/upload/cover-") and bool(moved2), str(b)[:160])

print("=" * 60)
print(f"封面接口专项完成：通过 {PASS} 项，失败 {FAIL} 项")
print("上传文件已移入：" + ART)
print("=" * 60)
sys.exit(1 if FAIL else 0)
