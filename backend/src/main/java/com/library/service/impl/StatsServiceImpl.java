package com.library.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.library.common.ResultCode;
import com.library.common.constant.BorrowStatusConstant;
import com.library.common.constant.ReservationStatusConstant;
import com.library.common.constant.RoleConstant;
import com.library.common.exception.BusinessException;
import com.library.entity.Book;
import com.library.entity.BorrowRecord;
import com.library.entity.Category;
import com.library.entity.FineRecord;
import com.library.entity.Publisher;
import com.library.entity.SysUser;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.CategoryMapper;
import com.library.mapper.FineRecordMapper;
import com.library.mapper.PublisherMapper;
import com.library.mapper.ReservationMapper;
import com.library.mapper.StatsMapper;
import com.library.mapper.SysUserMapper;
import com.library.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统计分析服务实现（聚合全部在 SQL 层完成）
 */
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final StatsMapper statsMapper;
    private final BookMapper bookMapper;
    private final BorrowRecordMapper borrowRecordMapper;
    private final SysUserMapper sysUserMapper;
    private final CategoryMapper categoryMapper;
    private final PublisherMapper publisherMapper;
    private final FineRecordMapper fineRecordMapper;
    private final ReservationMapper reservationMapper;

    private static final int MAX_EXPORT_DETAIL_ROWS = 20000;
    private static final int MAX_EXPORT_DAYS = 366;
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> data = new HashMap<>();
        LocalDate today = LocalDate.now();
        data.put("bookKinds", bookMapper.selectCount(null));
        data.put("totalStock", statsMapper.sumTotalStock());
        data.put("onBorrow", borrowRecordMapper.selectCount(
                new LambdaQueryWrapper<BorrowRecord>().eq(BorrowRecord::getStatus, BorrowStatusConstant.BORROWING)));
        data.put("readerCount", sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, RoleConstant.READER)));
        data.put("todayBorrow", borrowRecordMapper.selectCount(
                new LambdaQueryWrapper<BorrowRecord>().eq(BorrowRecord::getBorrowDate, today)));
        data.put("todayReturn", borrowRecordMapper.selectCount(
                new LambdaQueryWrapper<BorrowRecord>()
                        .eq(BorrowRecord::getStatus, BorrowStatusConstant.RETURNED)
                        .eq(BorrowRecord::getReturnDate, today)));
        return data;
    }

    @Override
    public Map<String, Object> trend() {
        // 12 个月窗口起点由 Java 计算（与 overview 的 LocalDate.now() 同源），统一业务日期基准
        LocalDate windowStart = LocalDate.now().minusMonths(11).withDayOfMonth(1);
        // SQL 层按月聚合，Java 只负责补齐 12 个月的缺口
        Map<String, Long> monthCount = new HashMap<>();
        for (Map<String, Object> row : statsMapper.selectBorrowTrend(windowStart)) {
            String month = String.valueOf(row.get("month"));
            Object cnt = row.get("cnt");
            monthCount.put(month, cnt == null ? 0L : ((Number) cnt).longValue());
        }
        YearMonth cursor = YearMonth.now().minusMonths(11);
        List<String> months = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // 月份标签带年份，避免跨年窗口（如 2025-10 ~ 2026-09）同月重名歧义
            months.add(cursor.getYear() + "年" + cursor.getMonthValue() + "月");
            values.add(monthCount.getOrDefault(cursor.toString(), 0L));
            cursor = cursor.plusMonths(1);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("months", months);
        data.put("values", values);
        return data;
    }

    @Override
    public List<Map<String, Object>> pie() {
        return statsMapper.selectCategoryPie();
    }

    @Override
    public List<Book> top() {
        // docs/05 要求「借阅 TOP10 报表」
        List<Book> books = bookMapper.selectList(
                new LambdaQueryWrapper<Book>()
                        .orderByDesc(Book::getBorrowCount)
                        .last("LIMIT 10"));
        // 填充分类名/出版社名
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
        return books;
    }

    @Override
    public byte[] exportReport(LocalDate startDate, LocalDate endDate) {
        LocalDate end = endDate == null ? LocalDate.now() : endDate;
        LocalDate start = startDate == null ? end.minusDays(29) : startDate;
        if (start.isAfter(end)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "开始日期不能晚于结束日期");
        }
        // 闭区间最多 366 个日历日：start=end 为 1 天，差值为 365 时仍允许；
        // 差值 366 表示 367 个日历日，必须拒绝（此前用 > 会多放 1 天）。
        if (ChronoUnit.DAYS.between(start, end) >= MAX_EXPORT_DAYS) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "导出区间不能超过 " + MAX_EXPORT_DAYS + " 天");
        }
        Long borrowCount = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                .between(BorrowRecord::getBorrowDate, start, end));
        Long fineCount = fineRecordMapper.selectCount(new LambdaQueryWrapper<FineRecord>()
                .ge(FineRecord::getCreateTime, start.atStartOfDay())
                .lt(FineRecord::getCreateTime, end.plusDays(1).atStartOfDay()));
        long detailRows = (borrowCount == null ? 0 : borrowCount) + (fineCount == null ? 0 : fineCount);
        if (detailRows > MAX_EXPORT_DETAIL_ROWS) {
            throw new BusinessException("导出数据超过 " + MAX_EXPORT_DETAIL_ROWS + " 行，请缩小日期范围");
        }

        List<Map<String, Object>> borrowRows = statsMapper.selectBorrowDetail(start, end);
        List<Map<String, Object>> fineRows = statsMapper.selectFineDetail(start, end);
        List<Book> topBooks = top();
        Map<String, Object> ov = overview();
        Long periodReturn = borrowRecordMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>()
                .eq(BorrowRecord::getStatus, BorrowStatusConstant.RETURNED)
                .between(BorrowRecord::getReturnDate, start, end));
        Long activeReservations = reservationMapper.selectCount(new LambdaQueryWrapper<com.library.entity.Reservation>()
                .in(com.library.entity.Reservation::getStatus,
                        ReservationStatusConstant.WAITING, ReservationStatusConstant.READY));
        BigDecimal fineAmount = fineRows.stream()
                .map(r -> r.get("amount") == null ? BigDecimal.ZERO
                        : new BigDecimal(String.valueOf(r.get("amount"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        List<List<String>> summary = new ArrayList<>();
        summary.add(List.of("统计区间", start + " 至 " + end));
        summary.add(List.of("图书品种", text(ov.get("bookKinds"))));
        summary.add(List.of("馆藏总册数", text(ov.get("totalStock"))));
        summary.add(List.of("当前在借", text(ov.get("onBorrow"))));
        summary.add(List.of("注册读者", text(ov.get("readerCount"))));
        summary.add(List.of("期间借出次数", text(borrowCount)));
        summary.add(List.of("期间归还次数", text(periodReturn)));
        summary.add(List.of("期间罚金笔数", text(fineRows.size())));
        summary.add(List.of("期间罚金金额（元）", fineAmount.toPlainString()));
        summary.add(List.of("当前有效预约（排队+待取）", text(activeReservations)));

        List<List<String>> borrowHead = List.of(
                List.of("记录ID"), List.of("读者证号"), List.of("读者姓名"), List.of("书名"), List.of("ISBN"),
                List.of("借出日期"), List.of("应还日期"), List.of("归还日期"), List.of("状态"), List.of("续借次数"));
        List<List<String>> borrowData = borrowRows.stream().map(r -> List.of(
                text(r.get("id")), text(r.get("readerNo")), text(r.get("readerName")),
                text(r.get("bookTitle")), text(r.get("isbn")), dateText(r.get("borrowDate")),
                dateText(r.get("dueDate")), dateText(r.get("returnDate")),
                borrowStatusText(r.get("status")), text(r.get("renewCount")))).toList();

        List<List<String>> fineHead = List.of(
                List.of("罚金ID"), List.of("读者证号"), List.of("读者姓名"), List.of("书名"),
                List.of("金额（元）"), List.of("类型"), List.of("状态"), List.of("缴纳时间"),
                List.of("登记时间"), List.of("备注"));
        List<List<String>> fineData = fineRows.stream().map(r -> List.of(
                text(r.get("id")), text(r.get("readerNo")), text(r.get("readerName")),
                text(r.get("bookTitle")), amountText(r.get("amount")), fineTypeText(r.get("type")),
                fineStatusText(r.get("status")), dateTimeText(r.get("paidTime")),
                dateTimeText(r.get("createTime")), text(r.get("remark")))).toList();

        List<List<String>> topHead = List.of(
                List.of("排名"), List.of("书名"), List.of("作者"), List.of("分类"), List.of("累计借阅次数"));
        List<List<String>> topData = new ArrayList<>();
        for (int i = 0; i < topBooks.size(); i++) {
            Book b = topBooks.get(i);
            topData.add(List.of(String.valueOf(i + 1), text(b.getTitle()), text(b.getAuthor()),
                    text(b.getCategoryName()), text(b.getBorrowCount())));
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExcelWriter writer = EasyExcel.write(output).build();
        writer.write(summary, EasyExcel.writerSheet(0, "汇总")
                .head(List.of(List.of("指标"), List.of("数值"))).build());
        writer.write(borrowData, EasyExcel.writerSheet(1, "借阅明细").head(borrowHead).build());
        writer.write(fineData, EasyExcel.writerSheet(2, "罚金明细").head(fineHead).build());
        writer.write(topData, EasyExcel.writerSheet(3, "热门图书").head(topHead).build());
        writer.finish();
        return output.toByteArray();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String amountText(Object value) {
        if (value == null) {
            return "";
        }
        return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String dateText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        if (value instanceof LocalDateTime dt) {
            return dt.toLocalDate().toString();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate().toString();
        }
        if (value instanceof java.util.Date d) {
            return new java.sql.Timestamp(d.getTime()).toLocalDateTime().toLocalDate().toString();
        }
        return String.valueOf(value);
    }

    private static String dateTimeText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDateTime dt) {
            return dt.format(DATE_TIME_FORMAT);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(DATE_TIME_FORMAT);
        }
        if (value instanceof java.util.Date d) {
            return new java.sql.Timestamp(d.getTime()).toLocalDateTime().format(DATE_TIME_FORMAT);
        }
        return String.valueOf(value);
    }

    private static String borrowStatusText(Object value) {
        if (value == null) {
            return "";
        }
        return switch (((Number) value).intValue()) {
            case 1 -> "在借";
            case 2 -> "已还";
            case 3 -> "已赔偿";
            default -> "";
        };
    }

    private static String fineTypeText(Object value) {
        if (value == null) {
            return "";
        }
        return switch (((Number) value).intValue()) {
            case 1 -> "逾期罚金";
            case 2 -> "破损赔偿";
            case 3 -> "遗失赔偿";
            default -> "";
        };
    }

    private static String fineStatusText(Object value) {
        if (value == null) {
            return "";
        }
        return ((Number) value).intValue() == 1 ? "未缴" : "已缴";
    }
}