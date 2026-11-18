package dev.mattest.supplychain.service;

import dev.mattest.supplychain.dto.catalog.CategoryRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.catalog.CategoryVO;

import java.util.List;

public interface CategoryService {
    List<CategoryVO> list();
    CategoryVO get(long id);
    CategoryVO create(AdminPrincipal actor, CategoryRequest request);
    CategoryVO update(AdminPrincipal actor, long id, CategoryRequest request);
    void setActive(AdminPrincipal actor, long id, boolean active);
    void delete(AdminPrincipal actor, long id);
}
