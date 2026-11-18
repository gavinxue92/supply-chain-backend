package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.mattest.supplychain.dto.catalog.CategoryRequest;
import dev.mattest.supplychain.entity.Category;
import dev.mattest.supplychain.mapper.CategoryMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.CategoryService;
import dev.mattest.supplychain.vo.catalog.CategoryVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class CategoryServiceImpl implements CategoryService {
    private final CategoryMapper categoryMapper;
    private final AuditLogWriter auditLogWriter;

    public CategoryServiceImpl(
            CategoryMapper categoryMapper,
            AuditLogWriter auditLogWriter) {
        this.categoryMapper = categoryMapper;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public List<CategoryVO> list() {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                        .orderByAsc(Category::getParentId)
                        .orderByAsc(Category::getSortOrder)
                        .orderByAsc(Category::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public CategoryVO get(long id) {
        return toView(findCategory(id));
    }

    @Override
    @Transactional
    public CategoryVO create(AdminPrincipal actor, CategoryRequest request) {
        String name = request.name().trim();
        validateParent(request.parentId(), null);

        Category category = new Category();
        category.setName(name);
        category.setParentId(request.parentId());
        category.setSortOrder(request.sortOrder());
        category.setIsActive(true);
        categoryMapper.insert(category);
        auditLogWriter.write(actor.id(), "CATEGORY_CREATED", "categories", category.getId(),
                Map.of("name", name, "parentId", valueOrEmpty(request.parentId()),
                        "sortOrder", request.sortOrder()));
        return toView(category);
    }

    @Override
    @Transactional
    public CategoryVO update(AdminPrincipal actor, long id, CategoryRequest request) {
        Category category = findCategory(id);
        String name = request.name().trim();
        validateParent(request.parentId(), id);
        if (categoryMapper.countChildrenIncludingDeleted(id) > 0 && request.parentId() != null) {
            throw badRequest("A category with children must remain a top-level category");
        }

        Long oldParentId = category.getParentId();
        String oldName = category.getName();
        int oldSortOrder = category.getSortOrder();
        category.setName(name);
        category.setParentId(request.parentId());
        category.setSortOrder(request.sortOrder());
        categoryMapper.updateById(category);
        auditLogWriter.write(actor.id(), "CATEGORY_UPDATED", "categories", id,
                Map.of("name", name, "parentId", valueOrEmpty(request.parentId()),
                        "sortOrder", request.sortOrder(),
                        "previousName", oldName,
                        "previousParentId", valueOrEmpty(oldParentId),
                        "previousSortOrder", oldSortOrder));
        return toView(categoryMapper.selectById(id));
    }

    @Override
    @Transactional
    public void setActive(AdminPrincipal actor, long id, boolean active) {
        Category category = findCategory(id);
        if (Boolean.TRUE.equals(category.getIsActive()) == active) {
            return;
        }
        categoryMapper.update(null, new LambdaUpdateWrapper<Category>()
                .eq(Category::getId, id)
                .set(Category::getIsActive, active));
        auditLogWriter.write(actor.id(), active ? "CATEGORY_ACTIVATED" : "CATEGORY_DEACTIVATED",
                "categories", id, Map.of("name", category.getName(), "active", active));
    }

    @Override
    @Transactional
    public void delete(AdminPrincipal actor, long id) {
        Category category = findCategory(id);
        if (categoryMapper.countChildrenIncludingDeleted(id) > 0
                || categoryMapper.countProductsIncludingArchived(id) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Category has child categories or associated products");
        }
        categoryMapper.deleteById(id);
        auditLogWriter.write(actor.id(), "CATEGORY_DELETED", "categories", id,
                Map.of("name", category.getName()));
    }

    private void validateParent(Long parentId, Long categoryId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(categoryId)) {
            throw badRequest("A category cannot be its own parent");
        }
        Category parent = categoryMapper.selectById(parentId);
        if (parent == null) {
            throw badRequest("Parent category does not exist");
        }
        if (parent.getParentId() != null) {
            throw badRequest("Categories can have at most two levels");
        }
    }

    private Category findCategory(long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
        }
        return category;
    }

    private CategoryVO toView(Category category) {
        if (category == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
        }
        return new CategoryVO(
                category.getId(),
                category.getParentId(),
                category.getName(),
                Boolean.TRUE.equals(category.getIsActive()),
                category.getSortOrder());
    }

    private Object valueOrEmpty(Long value) {
        return value == null ? "" : value;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
