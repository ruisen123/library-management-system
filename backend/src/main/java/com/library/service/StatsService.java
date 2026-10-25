package com.library.service;

import com.library.entity.Book;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 统计分析服务（数据总览 / 月度趋势 / 分类占比 / 借阅 TOP）
 */
public interface StatsService {

    /** 数据总览：图书品种、馆藏总册数、当前在借、注册读者、今日借出、今日归还 */
    Map<String, Object> overview();

    /** 近 12 个月借阅趋势 { months, values } */
    Map<String, Object> trend();

    /** 分类借阅占比 [{ name, value }] */
    List<Map<String, Object>> pie();

    /** 借阅热度 TOP5 图书 */
    List<Book> top();

    /**
     * 导出 Excel 报表（汇总 / 借阅明细 / 罚金明细 / 热门图书）。
     * 日期为空时默认近 30 天；范围超过 366 天或明细超过 20000 行时拒绝，避免一次导出拖垮服务。
     */
    byte[] exportReport(LocalDate startDate, LocalDate endDate);
}