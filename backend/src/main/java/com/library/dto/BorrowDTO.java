package com.library.dto;

import lombok.Data;

/**
 * 借还办理请求参数
 * 借书：userId + bookId；还书/续借：recordId
 */
@Data
public class BorrowDTO {

    /** 读者 ID（借书） */
    private Long userId;

    /** 图书 ID（借书） */
    private Long bookId;

    /** 借阅记录 ID（还书/续借） */
    private Long recordId;
}