package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 公告新增/编辑请求参数
 */
@Data
public class NoticeDTO {

    /** 标题 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题长度不能超过 100 个字符")
    private String title;

    /** 正文 */
    @NotBlank(message = "公告内容不能为空")
    @Size(max = 20000, message = "公告内容不能超过 20000 个字符")
    private String content;

    /** 状态：1已发布 0已撤回（新增时后端置为 1，编辑时可切换） */
    private Integer status;
}