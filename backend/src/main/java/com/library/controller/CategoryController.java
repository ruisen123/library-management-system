package com.library.controller;

import com.library.annotation.RequireRole;
import com.library.common.Result;
import com.library.common.constant.RoleConstant;
import com.library.entity.Category;
import com.library.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 图书分类控制器
 * 读：管理员 + 图书管理员（图书表单需要）+ 读者（检索页分类下拉）；写：仅系统管理员
 */
@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
@Tag(name = "04-分类与出版社模块")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @RequireRole({RoleConstant.ADMIN, RoleConstant.LIBRARIAN, RoleConstant.READER})
    @Operation(summary = "分类列表（读者检索页分类下拉共用）")
    public Result<List<Category>> list() {
        return Result.ok(categoryService.list());
    }

    @PostMapping
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "新增分类")
    public Result<Void> add(@RequestBody Category category) {
        categoryService.add(category);
        return Result.ok();
    }

    @PutMapping("/{id}")
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "编辑分类")
    public Result<Void> update(@PathVariable Long id, @RequestBody Category category) {
        category.setId(id);
        categoryService.update(category);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @RequireRole(RoleConstant.ADMIN)
    @Operation(summary = "删除分类")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.ok();
    }
}