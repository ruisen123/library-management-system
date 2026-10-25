package com.library.service;

import com.library.vo.AuditReportVO;

/**
 * 数据一致性巡检服务：只读诊断，检测并定位存量数据的不一致
 */
public interface AuditService {

    /** 执行一次全量巡检并返回报告（只读，不修改任何业务数据） */
    AuditReportVO run();
}
