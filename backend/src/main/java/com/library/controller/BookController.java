package com.library.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.ResultCode;
import com.library.common.constant.RoleConstant;
import com.library.common.exception.BusinessException;
import com.library.dto.BookDTO;
import com.library.dto.BookImportRow;
import com.library.entity.Book;
import com.library.service.BookService;
import com.library.vo.BookImportResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 图书档案控制器
 * 读：图书管理员 + 读者；写：仅图书管理员
 */
@Slf4j
@RestController
@RequestMapping("/books")
@RequiredArgsConstructor
@Tag(name = "03-图书模块")
public class BookController {

    private static final long MAX_IMPORT_FILE_BYTES = 5L * 1024L * 1024L;
    private static final int MAX_IMPORT_ROWS = 5000;

    /** 封面图片：类型白名单与大小上限（2026-10-25 新增上传能力时定义） */
    private static final long MAX_COVER_FILE_BYTES = 2L * 1024L * 1024L;
    private static final Set<String> ALLOWED_COVER_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");

    private final BookService bookService;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @GetMapping
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN, RoleConstant.READER})
    @Operation(summary = "分页查询图书")
    public Result<IPage<Book>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean onlyStock,
            @RequestParam(required = false) String sort) {
        // 分页参数归一化：MyBatis-Plus 在 size<1 时会跳过分页返回全表，此处强制下界为 1（上界由 maxLimit=1000 保障）
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        return Result.ok(bookService.page(page, size, keyword, categoryId, onlyStock, sort));
    }

    @GetMapping("/{id}")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN, RoleConstant.READER})
    @Operation(summary = "图书详情")
    public Result<Book> detail(@PathVariable Long id) {
        return Result.ok(bookService.getById(id));
    }

    @GetMapping("/import-template")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "下载图书批量导入模板")
    public void importTemplate(HttpServletResponse response) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExcelWriter writer = EasyExcel.write(output, BookImportRow.class).build();
        writer.write(Collections.<BookImportRow>emptyList(),
                EasyExcel.writerSheet(0, "图书导入模板").build());
        List<List<String>> noteHead = List.of(List.of("填写说明"));
        List<List<String>> notes = List.of(
                List.of("1. 分类、出版社必须与系统中已有名称完全一致（区分大小写）"),
                List.of("2. ISBN 支持 13 位数字或 10 位数字（末位可为 X），不可与系统已有 ISBN 重复"),
                List.of("3. 新导入图书的可借册数必须等于总册数"),
                List.of("4. 出版日期格式：yyyy-MM-dd，可留空"),
                List.of("5. 单次最多导入 5000 行；失败行会返回行号与原因，合法行正常入库")
        );
        writer.write(notes, EasyExcel.writerSheet(1, "填写说明").head(noteHead).build());
        writer.finish();
        writeXlsx(response, output.toByteArray(), "图书导入模板.xlsx");
    }

    /**
     * 上传图书封面图片（2026-10-25 新增）。
     * 为什么需要落盘端点：book.cover 是 VARCHAR(255)，图片本身只能以「地址」形式入库，
     * 故先把文件写入 uploadDir（已由 WebMvcConfig 映射为 /upload/**），返回可访问路径。
     * 安全：仅 ADMIN/LIBRARIAN；类型白名单；≤2MB；文件名随机化并做目录逃逸校验。
     */
    @PostMapping(value = "/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "上传图书封面图片")
    public Result<String> uploadCover(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择要上传的封面图片");
        }
        if (file.getSize() > MAX_COVER_FILE_BYTES) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "封面图片不能超过 2MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot).toLowerCase() : "";
        if (!ALLOWED_COVER_EXT.contains(ext)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "仅支持 .jpg / .jpeg / .png / .webp / .gif 图片");
        }
        try {
            // 文件名随机化：既避免同名互相覆盖，也让 ../ 之类的路径构造无从下手
            String name = "cover-" + UUID.randomUUID().toString().replace("-", "") + ext;
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(name).normalize();
            if (!target.startsWith(dir)) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "文件名不合法");
            }
            file.transferTo(target.toFile());
            log.info("封面上传成功：{}（{} 字节）", name, file.getSize());
            return Result.ok("/upload/" + name);
        } catch (IOException e) {
            log.error("封面写入失败：{}", e.getMessage());
            throw new BusinessException("封面保存失败，请稍后重试");
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "Excel 批量导入图书")
    public Result<BookImportResultVO> importBooks(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择要导入的 Excel 文件");
        }
        if (file.getSize() > MAX_IMPORT_FILE_BYTES) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "导入文件不能超过 5MB");
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "仅支持 .xlsx / .xls 文件");
        }
        // 随读随计（2026-10-24 审计修复）：原实现用 doReadSync() 把整表读进内存后才判 5000 行上限，
        // 5MB 的 xlsx 会先全量 materialize 再被拒；改为监听器逐行收集，超出上限立即置 hasNext=false，
        // EasyExcel 随即结束读取，不再为超限文件建满对象（详见 BookImportRowListener 注释）。
        BookImportRowListener listener = new BookImportRowListener(MAX_IMPORT_ROWS);
        try {
            EasyExcel.read(file.getInputStream(), BookImportRow.class, listener).sheet().doRead();
        } catch (Exception e) {
            log.warn("图书导入 Excel 解析失败：{}", e.getMessage());
            throw new BusinessException("Excel 解析失败，请使用系统模板并检查文件格式");
        }
        List<BookImportRow> rows = listener.getRows();
        if (listener.isOverLimit()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "单次最多导入 " + MAX_IMPORT_ROWS + " 行，请拆分文件");
        }
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "未读取到数据行，请检查文件内容");
        }
        BookImportResultVO result = bookService.importBooks(rows);
        return Result.ok("导入完成：成功 " + result.getSuccess() + " 本，失败 "
                + result.getFailed() + " 行", result);
    }

    @PostMapping
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "新增图书")
    public Result<Void> add(@Valid @RequestBody BookDTO dto) {
        bookService.add(dto);
        return Result.ok();
    }

    @PutMapping("/{id}")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "编辑图书")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody BookDTO dto) {
        bookService.update(id, dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "删除图书")
    public Result<Void> delete(@PathVariable Long id) {
        bookService.delete(id);
        return Result.ok();
    }

    /** 统一下载响应头：文件名用 RFC 5987 编码，避免中文文件名在浏览器端乱码 */
    private void writeXlsx(HttpServletResponse response, byte[] data, String fileName) throws IOException {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + encoded);
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }

    /**
     * 图书导入行监听器（2026-10-24 审计修复）
     *
     * 只做两件事：逐行收集、超过上限立即让 hasNext 返回 false。
     * hasNext=false 是 EasyExcel 3.3.4 的官方提前终止钩子：DefaultAnalysisEventProcessor 会抛出
     * ExcelAnalysisStopException，ExcelAnalyserImpl.analysis() 捕获后静默结束读取
     * （ReadListener#hasNext javadoc："You can stop the read by returning false"）。
     * 因此超限文件最多只建 maxRows+1 个业务对象，下游提示文案与原先"读完再拒"完全一致。
     */
    static final class BookImportRowListener implements ReadListener<BookImportRow> {

        private final int maxRows;
        private final List<BookImportRow> rows = new ArrayList<>();
        private boolean overLimit;

        BookImportRowListener(int maxRows) {
            this.maxRows = maxRows;
        }

        @Override
        public void invoke(BookImportRow data, AnalysisContext context) {
            if (rows.size() >= maxRows) {
                // 第 maxRows+1 行：只标记超限，不再收集；接下来的 hasNext 会终止读取
                overLimit = true;
                return;
            }
            rows.add(data);
        }

        @Override
        public boolean hasNext(AnalysisContext context) {
            return !overLimit;
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 无需收尾动作
        }

        List<BookImportRow> getRows() {
            return rows;
        }

        boolean isOverLimit() {
            return overLimit;
        }
    }
}