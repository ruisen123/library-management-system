# 容器生产链路封面上传实测（2026-10-26）

- **目的**：验证 `deploy/docker/` 完整链路的封面闭环——容器 Nginx 托管生产构建 → `/api` 登录/上传 → `/upload/**` 反代后端静态目录 → 列表 `<img>` 可解码。此前只测过“后端容器内直接调接口”与“Vite 开发链”，本条补齐容器生产链。
- **命令**：`docker compose -p libms-p1ui up -d --build` + `node .workbuddy/tmp/audit_20261025/cover_ui_container_probe.js`（Playwright，脚本在过程目录，不注册 `run_all`）
- **访问入口**：`http://localhost:19088`（仅前端端口；MySQL/后端不映射）
- **结果（退出码 0）**：

```json
{
  "quickLoginButtons": 0,
  "rows": 6,
  "coverVal": "/upload/cover-8656dac6d3c241ee9a32a793cad31dc0.png",
  "dialogGone": true,
  "imgSrc": "/upload/cover-8656dac6d3c241ee9a32a793cad31dc0.png",
  "natural": 1,
  "directStatus": 200,
  "errors": [],
  "failed": []
}
```

- **判定**：生产构建中演示快捷登录按钮为 0（提权入口已关闭）；图书列表 6 行；手工登录馆员后经 Nginx 上传成功；保存后列表 `img.src` 与返回路径一致、`naturalWidth=1`；`/upload/**` 经容器 Nginx 反代返回 200；控制台与失败请求 0。
- **收尾**：临时项目 `libms-p1ui` 已 `down -v`（容器/卷/网络全销毁），临时镜像已 `rmi`；Docker 守护进程已关闭；本机 `library_db` 未被触碰（仍 10 表 / 19 CHECK / 种子 6-4-4-6-8-3-3-3）。
- **口径**：容器链路手工专项 probing，**不计入 19 套件 585 项**，不改变专项 53 项口径。