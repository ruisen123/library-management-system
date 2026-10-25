@echo off
setlocal
cd /d "%~dp0"
set CHECKMODE=0
if /i "%~1"=="--check" set CHECKMODE=1

set MYSQL="C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
set DB_USERNAME=root
REM ========== 敏感配置：优先取环境变量，缺失时才回退到内置演示值 ==========
REM 数据库口令须与本机 MySQL 一致；JWT 密钥须 >=32 字节（JwtUtils 启动期校验）
REM 内置演示值仅限本机演示：交付包外发后他人可用该密钥离线伪造任意角色令牌，
REM 换机器/对外演示前请先设置环境变量 DB_PASSWORD / JWT_SECRET 覆盖（见下方启动横幅）。
set "USING_BUILTIN_SECRET=0"
if not defined DB_PASSWORD (
    set "DB_PASSWORD=123456"
    set "USING_BUILTIN_SECRET=1"
)
if not defined JWT_SECRET (
    set "JWT_SECRET=LibMS-2a3887092db6cefb65b3468a429e69fb-sk"
    set "USING_BUILTIN_SECRET=1"
)
set USERS=0

echo ============================================================
echo    LibMS 图书借阅管理系统 - 一键启动
echo    参数：--seed 空库时导入演示数据；--check 只检查不启动
echo ============================================================
echo.
if "%USING_BUILTIN_SECRET%"=="1" (
    echo **************************************************************
    echo  [安全提示] 本次使用脚本内置的演示口令/密钥，仅限本机演示！
    echo             拿到交付包的人可离线伪造任意角色的登录令牌：
    echo             请先 set DB_PASSWORD / JWT_SECRET 后再重跑本脚本。
    echo **************************************************************
    echo.
)

REM ========== 1/5 检查 MySQL ==========
echo [1/5] 检查 MySQL 服务 ...
%MYSQL% -u%DB_USERNAME% -p%DB_PASSWORD% -N -e "SELECT 1;" >nul 2>&1
if errorlevel 1 goto err_mysql
echo        MySQL 连接正常。
goto step2

:err_mysql
echo    [错误] 无法连接 MySQL，请先启动 MySQL 服务：
echo           services.msc 或 net start MySQL
echo           并确认数据库口令与本机 MySQL 一致（口令不同：先 set DB_PASSWORD=你的口令
echo           再重跑本脚本；或右键编辑本文件顶部的「敏感配置」段改成固定值）
pause
exit /b 1

REM ========== 2/5 检查数据 / 可选导入演示数据 ==========
:step2
echo [2/5] 检查数据库数据 ...
%MYSQL% -u%DB_USERNAME% -p%DB_PASSWORD% -N -e "SELECT COUNT(*) FROM library_db.sys_user;" > "%TEMP%\libms_users.txt" 2>nul
set /p USERS=<"%TEMP%\libms_users.txt"
del "%TEMP%\libms_users.txt" >nul 2>&1
if "%USERS%"=="" set USERS=0
echo        当前用户数：%USERS%
if not "%USERS%"=="0" goto step3
echo        [提示] 数据库当前为空（项目归零交付态）。
if /i "%~1"=="--seed" goto do_seed
echo        如需演示数据（6 个账号 / 6 本图书等），请执行：一键启动.bat --seed
goto step3

:do_seed
echo        正在导入演示数据 docs\04_数据库设计.sql ...
%MYSQL% -u%DB_USERNAME% -p%DB_PASSWORD% --default-character-set=utf8mb4 < "docs\04_数据库设计.sql"
echo        导入完成（账号 admin / librarian01 / reader001，密码均为 123456）。
goto step3

REM ========== 自检模式出口 ==========
:step3
if "%CHECKMODE%"=="1" goto check_end

where curl >nul 2>&1
if errorlevel 1 set NOCURL=1

REM ========== 3/5 启动后端 ==========
REM 环境变量（DB_USERNAME / DB_PASSWORD / JWT_SECRET）由本脚本导出（外部已设置的沿用外部值），子窗口自动继承
echo [3/5] 启动后端（新窗口，端口 8080）...
start "LibMS-Backend-8080" /d "%~dp0backend" cmd /k "java -jar target\libms-backend-1.0.0.jar --server.port=8080"
echo        后端窗口已打开，正在启动（约 5-10 秒，窗口里会滚动日志）...

if defined NOCURL goto wait_backend_fixed
echo        等待后端就绪（最多约 60 秒）...
call :wait_backend
goto step4

:wait_backend_fixed
echo        等待后端启动（约 12 秒）...
timeout /t 12 /nobreak >nul
goto step4

REM ========== 4/5 启动前端 ==========
:step4
echo [4/5] 启动前端（新窗口，端口 5173）...
start "LibMS-Frontend-5173" /d "%~dp0frontend" cmd /k "npm run dev -- --port 5173 --strictPort"

if defined NOCURL goto wait_frontend_fixed
echo        等待前端就绪（最多约 45 秒）...
call :wait_frontend
goto step5

:wait_frontend_fixed
echo        等待前端启动（约 10 秒）...
timeout /t 10 /nobreak >nul
goto step5

REM ========== 5/5 打开浏览器 ==========
:step5
echo [5/5] 打开浏览器 ...
start http://localhost:5173

echo.
echo ============================================================
echo   启动完成
echo     - 前端页面： http://localhost:5173
echo     - 后端接口： http://localhost:8080/doc.html
echo     - 演示账号： admin / librarian01 / reader001（密码 123456）
echo     - 若页面提示网络异常：后端可能还在启动，稍等十几秒刷新即可
echo     - 关闭系统：关闭上述两个程序窗口即可
echo ============================================================
echo.
pause
exit /b 0

:check_end
echo.
echo [自检模式] 全部检查通过，未启动任何服务、未打开浏览器。
exit /b 0

REM ========== 子程序：等待后端就绪 ==========
REM  探测：--noproxy 强制直连（绕过系统代理）、-m 3 单次超时 3 秒、-f 非 200 视为未就绪
:wait_backend
set TRIES=0
:wb_loop
set /a TRIES+=1
if %TRIES% gtr 20 goto wb_fail
timeout /t 2 /nobreak >nul
curl -s -f -m 3 --noproxy "*" -o nul http://localhost:8080/doc.html
if not errorlevel 1 goto wb_ok
if %TRIES% lss 20 echo        等待中（%TRIES%/20）...
goto wb_loop
:wb_fail
echo        [提示] 后端暂未就绪。请看后端窗口是否有报错；若正在启动，稍等后刷新网页即可。
goto :eof
:wb_ok
echo        后端已就绪（http://localhost:8080/doc.html）。
goto :eof

REM ========== 子程序：等待前端就绪 ==========
:wait_frontend
set FRIES=0
:fe_loop
set /a FRIES+=1
if %FRIES% gtr 15 goto fe_fail
timeout /t 2 /nobreak >nul
curl -s -f -m 3 --noproxy "*" -o nul http://localhost:5173/
if not errorlevel 1 goto fe_ok
if %FRIES% lss 15 echo        等待中（%FRIES%/15）...
goto fe_loop
:fe_fail
echo        [提示] 前端暂未就绪，请看前端窗口的输出。
goto :eof
:fe_ok
echo        前端已就绪（http://localhost:5173）。
goto :eof
