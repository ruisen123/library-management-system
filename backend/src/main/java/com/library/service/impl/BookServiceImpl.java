package com.library.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.common.ResultCode;
import com.library.common.constant.BorrowStatusConstant;
import com.library.common.constant.ReservationStatusConstant;
import com.library.common.exception.BusinessException;
import com.library.dto.BookDTO;
import com.library.dto.BookImportRow;
import com.library.entity.Book;
import com.library.entity.BorrowRecord;
import com.library.entity.Category;
import com.library.entity.Publisher;
import com.library.entity.Reservation;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.CategoryMapper;
import com.library.mapper.PublisherMapper;
import com.library.mapper.ReservationMapper;
import com.library.service.BookService;
import com.library.service.OperationLogService;
import com.library.service.ReservationService;
import com.library.utils.SqlLikeUtils;
import com.library.vo.BookImportResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 图书档案服务实现
 */
@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final BookMapper bookMapper;
    private final CategoryMapper categoryMapper;
    private final PublisherMapper publisherMapper;
    private final BorrowRecordMapper borrowRecordMapper;
    private final ReservationMapper reservationMapper;
    private final OperationLogService operationLogService;
    private final ReservationService reservationService;

    @Override
    public IPage<Book> page(int page, int size, String keyword, Long categoryId, Boolean onlyStock, String sort) {
        if (StrUtil.isNotBlank(sort) && !"popular".equals(sort)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "不支持的排序方式");
        }
        LambdaQueryWrapper<Book> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            // 关键字按字面量匹配（%/_ 已转义，显式 ESCAPE，2026-10-24 审计修复）；
            // 列名 = book.title / book.author / book.isbn（docs/04_数据库设计.sql:98-100）
            String like = SqlLikeUtils.likePattern(keyword);
            wrapper.and(w -> w.apply(SqlLikeUtils.likeCondition("title"), like)
                    .or().apply(SqlLikeUtils.likeCondition("author"), like)
                    .or().apply(SqlLikeUtils.likeCondition("isbn"), like));
        }
        if (categoryId != null) {
            wrapper.eq(Book::getCategoryId, categoryId);
        }
        if (Boolean.TRUE.equals(onlyStock)) {
            wrapper.gt(Book::getStock, 0);
        }
        if ("popular".equals(sort)) {
            wrapper.orderByDesc(Book::getBorrowCount).orderByDesc(Book::getId);
        } else {
            wrapper.orderByDesc(Book::getId);
        }
        IPage<Book> result = bookMapper.selectPage(new Page<>(page, size), wrapper);
        fillNames(result.getRecords());
        fillReservedCount(result.getRecords());
        return result;
    }

    @Override
    public Book getById(Long id) {
        Book book = bookMapper.selectById(id);
        if (book == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "图书不存在");
        }
        fillNames(List.of(book));
        fillReservedCount(List.of(book));
        return book;
    }

    /**
     * 填充"待取书"预约数，供前端展示真实可借数。
     * 待取预约保留库存（不扣 stock，巡检 R1 口径不变），只有借走时才扣，
     * 因此"可借多少"必须用 stock - reservedCount 表达，否则显示会与事实不符。
     */
    private void fillReservedCount(List<Book> books) {
        if (books == null || books.isEmpty()) {
            return;
        }
        List<Long> ids = books.stream().map(Book::getId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, Integer> reserved = reservationMapper.countReadyByBookIds(ids).stream()
                .collect(Collectors.toMap(
                        m -> ((Number) m.get("bookId")).longValue(),
                        m -> ((Number) m.get("cnt")).intValue(),
                        (a, b) -> a));
        books.forEach(b -> b.setReservedCount(reserved.getOrDefault(b.getId(), 0)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(BookDTO dto) {
        // ISBN 唯一性校验（@TableLogic 自动排除已删数据）
        Long isbnCount = bookMapper.selectCount(
                new LambdaQueryWrapper<Book>().eq(Book::getIsbn, dto.getIsbn()));
        if (isbnCount > 0) {
            throw new BusinessException("ISBN 已存在");
        }
        checkRef(dto);
        validateStockSelfConsistent(dto.getTotalStock(), dto.getStock(), 0, 0);
        Book book = new Book();
        copyDto(dto, book);
        book.setBorrowCount(0);
        bookMapper.insert(book);
        // 管理侧写操作审计留痕（与借还/登录口径一致）
        operationLogService.record("BOOK_ADD", "新增图书《" + dto.getTitle() + "》");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BookDTO dto) {
        // 先对图书行加锁再读取/校验/更新：借书事务的条件扣减（UPDATE book SET stock=stock-1）
        // 持有同一行 X 锁，二者互斥。否则并发借书可在"统计在借数"与"整行 set stock"之间提交，
        // 编辑会用旧 stock 覆盖借书的扣减，造成库存虚增（total-stock 与在借数不再相等，巡检 R1 事后才报）。
        bookMapper.lockByIdForUpdate(id);
        Book exist = bookMapper.selectByIdForUpdate(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "图书不存在");
        }
        Long isbnCount = bookMapper.selectCount(
                new LambdaQueryWrapper<Book>()
                        .eq(Book::getIsbn, dto.getIsbn())
                        .ne(Book::getId, id));
        if (isbnCount > 0) {
            throw new BusinessException("ISBN 已存在");
        }
        checkRef(dto);
        // 在借册数：总册数 - 可借数 不得小于 当前在借+已赔偿册数，防止把库存调成负数账目
        long onLoan = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                .eq(BorrowRecord::getBookId, id)
                .in(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING, BorrowStatusConstant.COMPENSATED));
        // 待取预约数：可借数不得低于待取数，否则待取读者到馆后必然借不到（超发）。
        // 本事务的读视图在图书行锁之后建立；持有图书行锁期间，READY 数只可能因取消/过期而减少
        // 或持平（取消一个 READY 再顺延一个 WAITING），不可能增加，故普通快照读即可，无需锁预约表。
        Long readyCnt = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getBookId, id)
                .eq(Reservation::getStatus, ReservationStatusConstant.READY));
        int ready = readyCnt == null ? 0 : readyCnt.intValue();
        validateStockSelfConsistent(dto.getTotalStock(), dto.getStock(), (int) onLoan, ready);
        // 显式 set 提交全部档案字段：MyBatis-Plus 的 updateById 默认跳过 null 字段，
        // 会导致"清空出版日期/定价"（日期/数字控件清空得到 null）无法写入，故改用条件更新逐字段赋值。
        // borrow_count 不在 set 之列，保持原值不被回写。
        bookMapper.update(null, new LambdaUpdateWrapper<Book>()
                .eq(Book::getId, id)
                .set(Book::getIsbn, dto.getIsbn())
                .set(Book::getTitle, dto.getTitle())
                .set(Book::getAuthor, dto.getAuthor())
                .set(Book::getCategoryId, dto.getCategoryId())
                .set(Book::getPublisherId, dto.getPublisherId())
                .set(Book::getPublishDate, dto.getPublishDate())
                .set(Book::getPrice, dto.getPrice())
                .set(Book::getTotalStock, dto.getTotalStock())
                .set(Book::getStock, dto.getStock())
                .set(Book::getLocation, dto.getLocation())
                .set(Book::getCover, dto.getCover())
                .set(Book::getDescription, dto.getDescription())
                .set(Book::getUpdateTime, LocalDateTime.now()));
        // 库存增加（采购入库/调整）后立即顺延预约队首：
        // 此前只有"还书"会推进队列，库存涨了队列仍停在排队中，直到下一次有人还书。
        int oldStock = exist.getStock() == null ? 0 : exist.getStock();
        int newStock = dto.getStock() == null ? 0 : dto.getStock();
        if (newStock > oldStock) {
            reservationService.promoteAfterReturn(id);
        }
        operationLogService.record("BOOK_UPDATE", "编辑图书ID " + id + "《" + dto.getTitle() + "》");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 先对图书行加锁再读取守卫：借书事务的 UPDATE book（扣库存）持有同一行 X 锁，二者互斥。
        // 若不加锁，并发借书可在"守卫读取（未见在借）→ 删除"之间提交，删除得手后仍存在在借记录，
        // 后续还书的库存回补将落空（账实不符）。锁与守卫同事务，保证守卫读到的是加锁后的最新状态。
        bookMapper.lockByIdForUpdate(id);
        Book exist = bookMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "图书不存在");
        }
        // 在借中禁止删除（docs/05 铁律）
        Long onLoan = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                .eq(BorrowRecord::getBookId, id)
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING));
        if (onLoan > 0) {
            throw new BusinessException("该图书存在在借记录，禁止删除");
        }
        // 预约护栏：有排队中/待取预约时删除会让预约永久悬挂，先让馆员处理预约
        if (reservationService.hasActiveReservation(id)) {
            throw new BusinessException("该图书存在有效预约，请先处理预约后再删除");
        }
        bookMapper.deleteById(id);
        operationLogService.record("BOOK_DELETE", "删除图书ID " + id);
    }

    /** 校验分类/出版社必须存在（提前给出友好提示，而非依赖外键报错） */
    private void checkRef(BookDTO dto) {
        if (categoryMapper.selectById(dto.getCategoryId()) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "所选分类不存在");
        }
        if (publisherMapper.selectById(dto.getPublisherId()) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "所选出版社不存在");
        }
    }

    /** 库存自洽（docs/04）：总册数 = 可借数 + 在借 + 已赔偿，双向约束（既防负账目，也防“幽灵册数”） */
    private void validateStockSelfConsistent(Integer totalStock, Integer stock, int heldCount, int readyCount) {
        if (totalStock == null || stock == null || totalStock < 0 || stock < 0) {
            throw new BusinessException("册数不能为负数");
        }
        if (stock > totalStock) {
            throw new BusinessException("可借册数不能大于总册数");
        }
        if (totalStock - stock != heldCount) {
            throw new BusinessException("馆藏册数不自洽：总册数 - 可借数 必须等于在借+已赔偿册数（"
                    + heldCount + "），请核对馆藏");
        }
        if (readyCount > stock) {
            throw new BusinessException("可借册数不能低于待取预约数（当前待取 " + readyCount
                    + " 册），请先处理预约或增加馆藏");
        }
    }

    private void copyDto(BookDTO dto, Book book) {
        book.setIsbn(dto.getIsbn());
        book.setTitle(dto.getTitle());
        book.setAuthor(dto.getAuthor());
        book.setCategoryId(dto.getCategoryId());
        book.setPublisherId(dto.getPublisherId());
        book.setPublishDate(dto.getPublishDate());
        book.setPrice(dto.getPrice());
        book.setTotalStock(dto.getTotalStock());
        book.setStock(dto.getStock());
        book.setLocation(dto.getLocation());
        book.setCover(dto.getCover());
        book.setDescription(dto.getDescription());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BookImportResultVO importBooks(List<BookImportRow> rows) {
        BookImportResultVO result = new BookImportResultVO();
        if (rows == null || rows.isEmpty()) {
            return result;
        }
        Map<String, Long> categoryMap = categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(c -> c.getName().trim(), Category::getId, (a, b) -> a));
        Map<String, Long> publisherMap = publisherMapper.selectList(null).stream()
                .collect(Collectors.toMap(p -> p.getName().trim(), Publisher::getId, (a, b) -> a));
        // 预取文件内 ISBN 对应的现有图书（含逻辑删除过滤），减少逐行查库
        List<String> isbns = rows.stream().map(BookImportRow::getIsbn)
                .filter(StrUtil::isNotBlank).map(String::trim).map(s -> s.toUpperCase(Locale.ROOT))
                .distinct().toList();
        Set<String> existing = new HashSet<>();
        if (!isbns.isEmpty()) {
            bookMapper.selectList(new LambdaQueryWrapper<Book>().in(Book::getIsbn, isbns))
                    .forEach(b -> existing.add(b.getIsbn().toUpperCase(Locale.ROOT)));
        }
        Set<String> seen = new HashSet<>();
        int rowNo = 1; // Excel 第 1 行为表头
        for (BookImportRow row : rows) {
            rowNo++;
            if (isBlankImportRow(row)) {
                continue;
            }
            result.setTotal(result.getTotal() + 1);
            try {
                Book book = toImportBook(row, categoryMap, publisherMap, seen, existing);
                bookMapper.insert(book);
                result.setSuccess(result.getSuccess() + 1);
            } catch (IllegalArgumentException e) {
                addImportError(result, rowNo, e.getMessage());
            }
            // 说明：数据库级异常（如并发导入同一 ISBN 撞唯一索引）不在此捕获，
            // 由整批事务回滚并交由 GlobalExceptionHandler 返回统一提示，避免在事务内吞异常后提交状态不确定。
        }
        if (result.getTotal() == 0) {
            throw new BusinessException("未读取到有效数据行，请检查模板是否填写内容");
        }
        if (result.getFailed() > result.getErrors().size()) {
            result.getErrors().add("……其余 " + (result.getFailed() - result.getErrors().size())
                    + " 条失败明细已省略");
        }
        operationLogService.record("BOOK_IMPORT", String.format(
                "Excel 批量导入图书：共 %d 行，成功 %d 本，失败 %d 行",
                result.getTotal(), result.getSuccess(), result.getFailed()));
        return result;
    }

    /** 整行都为空则跳过（Excel 末尾常见空行） */
    private boolean isBlankImportRow(BookImportRow row) {
        return StrUtil.isBlank(row.getIsbn()) && StrUtil.isBlank(row.getTitle())
                && StrUtil.isBlank(row.getAuthor()) && StrUtil.isBlank(row.getCategoryName())
                && StrUtil.isBlank(row.getPublisherName()) && StrUtil.isBlank(row.getPublishDate())
                && row.getPrice() == null && row.getTotalStock() == null && row.getStock() == null
                && StrUtil.isBlank(row.getLocation()) && StrUtil.isBlank(row.getDescription());
    }

    private void addImportError(BookImportResultVO result, int rowNo, String message) {
        result.setFailed(result.getFailed() + 1);
        if (result.getErrors().size() < 100) {
            result.getErrors().add("第 " + rowNo + " 行：" + message);
        }
    }

    /** 单行校验并转换为实体；校验失败抛 IllegalArgumentException（由调用方转成行级错误） */
    private Book toImportBook(BookImportRow row, Map<String, Long> categoryMap,
                              Map<String, Long> publisherMap, Set<String> seen, Set<String> existing) {
        String isbn = StrUtil.trimToNull(row.getIsbn());
        if (isbn == null) {
            throw new IllegalArgumentException("ISBN 不能为空");
        }
        isbn = isbn.toUpperCase(Locale.ROOT);
        if (!isbn.matches("^(?:[0-9]{13}|[0-9]{9}[0-9X])$")) {
            throw new IllegalArgumentException("ISBN 格式不正确（13 位数字或 10 位数字/X）");
        }
        if (seen.contains(isbn)) {
            throw new IllegalArgumentException("文件内 ISBN 重复");
        }
        if (existing.contains(isbn)) {
            throw new IllegalArgumentException("ISBN 已存在");
        }
        String title = StrUtil.trimToNull(row.getTitle());
        if (title == null) {
            throw new IllegalArgumentException("书名不能为空");
        }
        if (title.length() > 100) {
            throw new IllegalArgumentException("书名不能超过 100 个字符");
        }
        String author = StrUtil.trimToNull(row.getAuthor());
        if (author == null) {
            throw new IllegalArgumentException("作者不能为空");
        }
        if (author.length() > 100) {
            throw new IllegalArgumentException("作者不能超过 100 个字符");
        }
        String categoryName = StrUtil.trimToNull(row.getCategoryName());
        if (categoryName == null) {
            throw new IllegalArgumentException("分类不能为空");
        }
        Long categoryId = categoryMap.get(categoryName);
        if (categoryId == null) {
            throw new IllegalArgumentException("分类不存在：" + categoryName);
        }
        String publisherName = StrUtil.trimToNull(row.getPublisherName());
        if (publisherName == null) {
            throw new IllegalArgumentException("出版社不能为空");
        }
        Long publisherId = publisherMap.get(publisherName);
        if (publisherId == null) {
            throw new IllegalArgumentException("出版社不存在：" + publisherName);
        }
        Integer totalStock = row.getTotalStock();
        if (totalStock == null) {
            throw new IllegalArgumentException("总册数不能为空");
        }
        if (totalStock < 0 || totalStock > 100000) {
            throw new IllegalArgumentException("总册数需在 0-100000 之间");
        }
        Integer stock = row.getStock() == null ? totalStock : row.getStock();
        if (stock < 0 || stock > 100000) {
            throw new IllegalArgumentException("可借册数需在 0-100000 之间");
        }
        if (!stock.equals(totalStock)) {
            // 新导入图书还没有借阅流水，库存自洽要求 总册数=可借数
            throw new IllegalArgumentException("新书可借册数必须等于总册数（" + totalStock + "）");
        }
        BigDecimal price = row.getPrice();
        if (price != null && (price.signum() < 0 || price.compareTo(BigDecimal.valueOf(1000000)) > 0)) {
            throw new IllegalArgumentException("定价需在 0-1000000 元之间");
        }
        LocalDate publishDate = parseImportDate(row.getPublishDate());
        String location = StrUtil.trimToNull(row.getLocation());
        if (location != null && location.length() > 50) {
            throw new IllegalArgumentException("馆藏位置不能超过 50 个字符");
        }
        String description = StrUtil.trimToNull(row.getDescription());
        if (description != null && description.length() > 2000) {
            throw new IllegalArgumentException("内容简介不能超过 2000 个字符");
        }

        seen.add(isbn);
        Book book = new Book();
        book.setIsbn(isbn);
        book.setTitle(title);
        book.setAuthor(author);
        book.setCategoryId(categoryId);
        book.setPublisherId(publisherId);
        book.setPublishDate(publishDate);
        book.setPrice(price);
        book.setTotalStock(totalStock);
        book.setStock(stock);
        book.setBorrowCount(0);
        book.setLocation(location);
        book.setDescription(description);
        return book;
    }

    private LocalDate parseImportDate(String raw) {
        String value = StrUtil.trimToNull(raw);
        if (value == null) {
            return null;
        }
        // 兼容 Excel 日期单元格读出的 "yyyy-MM-dd HH:mm:ss" / "yyyy/M/d HH:mm:ss"
        if (value.length() > 10) {
            value = value.substring(0, 10).trim();
        }
        for (DateTimeFormatter formatter : IMPORT_DATE_FORMATS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        throw new IllegalArgumentException("出版日期格式应为 yyyy-MM-dd");
    }

    private static final List<DateTimeFormatter> IMPORT_DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d")
    );

    /** 批量填充分类名/出版社名（小数据量下比 XML 联表更简单可控） */
    private void fillNames(List<Book> books) {
        if (books.isEmpty()) {
            return;
        }
        List<Long> categoryIds = books.stream().map(Book::getCategoryId).filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> publisherIds = books.stream().map(Book::getPublisherId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Long, String> publisherNames = publisherIds.isEmpty() ? Map.of()
                : publisherMapper.selectBatchIds(publisherIds).stream()
                .collect(Collectors.toMap(Publisher::getId, Publisher::getName));
        books.forEach(book -> {
            book.setCategoryName(categoryNames.get(book.getCategoryId()));
            book.setPublisherName(publisherNames.get(book.getPublisherId()));
        });
    }
}