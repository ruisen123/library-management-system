# -*- coding: utf-8 -*-
"""极简 XLSX 读写工具（纯标准库，供导入/导出套件使用）

只覆盖本项目回归所需的单 sheet 生成与多 sheet 读取：
  · make_xlsx(path, rows)  —— 生成 inlineStr 单元格的 xlsx（EasyExcel 可读）
  · read_xlsx(path)        —— 读取 xlsx 为 {sheetName: [[cell,...],...]}
  · multipart(field, filename, content, content_type) —— 构造 multipart/form-data 请求体
"""
import zipfile
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def _col(n):
    s = ""
    while n > 0:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def _cell(ref, value):
    if value is None:
        return ""
    if isinstance(value, (int, float)):
        return f'<c r="{ref}"><v>{value}</v></c>'
    return (f'<c r="{ref}" t="inlineStr"><is>'
            f'<t xml:space="preserve">{escape(str(value))}</t></is></c>')


def make_xlsx(path, rows):
    sheet_rows = []
    for i, row in enumerate(rows, 1):
        cells = "".join(_cell(f"{_col(j)}{i}", v) for j, v in enumerate(row, 1))
        sheet_rows.append(f'<row r="{i}">{cells}</row>')
    sheet = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
             '<sheetData>' + "".join(sheet_rows) + '</sheetData></worksheet>')
    files = {
        '[Content_Types].xml': (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
            '</Types>'),
        '_rels/.rels': (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
            '</Relationships>'),
        'xl/workbook.xml': (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>'),
        'xl/_rels/workbook.xml.rels': (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
            '</Relationships>'),
        'xl/styles.xml': (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>'
            '<fills count="1"><fill><patternFill patternType="none"/></fill></fills>'
            '<borders count="1"><border/></borders>'
            '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
            '<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>'
            '</styleSheet>'),
        'xl/worksheets/sheet1.xml': sheet,
    }
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, content in files.items():
            z.writestr(name, content)


def read_xlsx(path):
    z = zipfile.ZipFile(path)
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        root = ET.fromstring(z.read("xl/sharedStrings.xml"))
        for si in root.findall(NS + "si"):
            shared.append("".join(t.text or "" for t in si.iter(NS + "t")))
    wb = ET.fromstring(z.read("xl/workbook.xml"))
    names = [s.get("name") for s in wb.find(NS + "sheets")]
    out = {}
    for idx, name in enumerate(names, 1):
        root = ET.fromstring(z.read(f"xl/worksheets/sheet{idx}.xml"))
        rows = []
        for row in root.iter(NS + "row"):
            vals = []
            for c in row.findall(NS + "c"):
                t = c.get("t")
                v = c.find(NS + "v")
                if t == "s" and v is not None:
                    val = shared[int(v.text)]
                elif t == "inlineStr":
                    val = "".join(x.text or "" for x in c.iter(NS + "t"))
                else:
                    val = v.text if v is not None else ""
                vals.append(val)
            rows.append(vals)
        out[name] = rows
    return out


def multipart(field, filename, content, content_type):
    boundary = "----LibMSBoundary" + filename.replace(".", "")
    head = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n").encode("utf-8")
    tail = f"\r\n--{boundary}--\r\n".encode("utf-8")
    return head + content + tail, f"multipart/form-data; boundary={boundary}"
