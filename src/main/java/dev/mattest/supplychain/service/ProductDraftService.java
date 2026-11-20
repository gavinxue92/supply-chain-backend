package dev.mattest.supplychain.service;

import dev.mattest.supplychain.dto.catalog.ProductDraftRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.catalog.ProductDraftVO;

import java.util.List;

public interface ProductDraftService {
    List<ProductDraftVO> list();
    ProductDraftVO get(long id);
    ProductDraftVO create(AdminPrincipal actor, ProductDraftRequest request);
    ProductDraftVO update(AdminPrincipal actor, long id, ProductDraftRequest request);
    ProductDraftVO publish(AdminPrincipal actor, long id);
    ProductDraftVO unpublish(AdminPrincipal actor, long id);
    ProductDraftVO archive(AdminPrincipal actor, long id);
    ProductDraftVO restore(AdminPrincipal actor, long id);
    void hardDelete(AdminPrincipal actor, long id);
}
