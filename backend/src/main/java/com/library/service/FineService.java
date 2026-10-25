package com.library.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.library.dto.CompensateDTO;
import com.library.entity.FineRecord;

/**
 * 罚金服务：逾期罚金查询 / 线下收款标记 / 破损遗失赔偿登记
 */
public interface FineService {

    /** 分页查询（读者角色强制过滤为本人记录，防越权查看他人罚金） */
    IPage<FineRecord> page(int page, int size, Integer status, Long userId);

    /** 线下收取后标记已缴 */
    void pay(Long id);

    /** 破损/遗失赔偿登记：记录改为“已赔偿”，罚款入库，库存不恢复 */
    void compensate(CompensateDTO dto);
}