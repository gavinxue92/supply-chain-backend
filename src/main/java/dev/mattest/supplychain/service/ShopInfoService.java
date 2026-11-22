package dev.mattest.supplychain.service;

import dev.mattest.supplychain.dto.shop.ShopInfoRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.shop.ShopInfoVO;

public interface ShopInfoService {
    ShopInfoVO getPublic();
    ShopInfoVO getForAdmin(AdminPrincipal actor);
    ShopInfoVO save(AdminPrincipal actor, ShopInfoRequest request);
}
