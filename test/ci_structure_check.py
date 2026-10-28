# -*- coding: utf-8 -*-
"""结构断言：守住 README 中的关键数字与代码实际一致。

在 CI 中运行；本地亦可执行：
    python test/ci_structure_check.py

断言项（与 README「技术栈」一节的规模描述一一对应）：
    · 控制器数量、@XxxMapping 接口数量
    · frontend/src/views 下的页面组件数量
    · docs/04_数据库设计.sql 中 CREATE TABLE 数量

只做静态统计，不连接数据库、不启动服务，因此可在任意环境重复执行。
"""
import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

ROOT = Path(__file__).resolve().parent.parent
CONTROLLER_DIR = ROOT / 'backend' / 'src' / 'main' / 'java' / 'com' / 'library' / 'controller'
VIEWS_DIR = ROOT / 'frontend' / 'src' / 'views'
SQL_FILE = ROOT / 'docs' / '04_数据库设计.sql'

ENDPOINT_PATTERN = re.compile(r'@(?:Get|Post|Put|Delete|Patch)Mapping')
TABLE_PATTERN = re.compile(r'CREATE\s+TABLE', re.IGNORECASE)


def count_controllers():
    return len([p for p in CONTROLLER_DIR.glob('*.java')])


def count_endpoints():
    total = 0
    for p in CONTROLLER_DIR.glob('*.java'):
        total += len(ENDPOINT_PATTERN.findall(p.read_text(encoding='utf-8')))
    return total


def count_views():
    return len(list(VIEWS_DIR.rglob('*.vue')))


def count_tables():
    return len(TABLE_PATTERN.findall(SQL_FILE.read_text(encoding='utf-8')))


def main():
    missing = [str(p) for p in (CONTROLLER_DIR, VIEWS_DIR, SQL_FILE) if not p.exists()]
    if missing:
        print('STRUCTURE_CHECK=FAIL 缺少路径: ' + ', '.join(missing))
        return 1

    checks = [
        ('controllers', count_controllers(), 15),
        ('endpoints', count_endpoints(), 59),
        ('vue_views', count_views(), 17),
        ('db_tables', count_tables(), 10),
    ]

    print('{:<16}{:>8}{:>8}   {}'.format('item', 'actual', 'expect', 'result'))
    print('-' * 46)
    failures = []
    for name, actual, expect in checks:
        ok = actual == expect
        print('{:<16}{:>8}{:>8}   {}'.format(name, actual, expect, 'OK' if ok else 'FAIL'))
        if not ok:
            failures.append('{} (actual={} expect={})'.format(name, actual, expect))

    print()
    if failures:
        print('STRUCTURE_CHECK=FAIL ' + '; '.join(failures))
        return 1
    print('STRUCTURE_CHECK=PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
