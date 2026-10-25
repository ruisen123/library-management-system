package com.library.controller;

import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.entity.Publisher;
import com.library.service.PublisherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 出版社控制器
 * 读：管理员 + 图书管理员；写：仅系统管理员
 */
@RestController
@RequestMapping("/publishers")
@RequiredArgsConstructor
@Tag(name = "04-分类与出版社模块")
public class PublisherController {

    private final PublisherService publisherService;

    @GetMapping
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN})
    @Operation(summary = "出版社列表")
    public Result<List<Publisher>> list() {
        return Result.ok(publisherService.list());
    }

    @PostMapping
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "新增出版社")
    public Result<Void> add(@RequestBody Publisher publisher) {
        publisherService.add(publisher);
        return Result.ok();
    }

    @PutMapping("/{id}")
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "编辑出版社")
    public Result<Void> update(@PathVariable Long id, @RequestBody Publisher publisher) {
        publisher.setId(id);
        publisherService.update(publisher);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "删除出版社")
    public Result<Void> delete(@PathVariable Long id) {
        publisherService.delete(id);
        return Result.ok();
    }
}