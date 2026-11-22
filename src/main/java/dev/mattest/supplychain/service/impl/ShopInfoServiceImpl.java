package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.mattest.supplychain.dto.shop.ShopInfoRequest;
import dev.mattest.supplychain.entity.ShopInfo;
import dev.mattest.supplychain.mapper.ShopInfoMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.ShopInfoService;
import dev.mattest.supplychain.vo.shop.ShopInfoVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ShopInfoServiceImpl implements ShopInfoService {
    private static final String CONFIG_KEY = "DEFAULT";

    private final ShopInfoMapper shopMapper;
    private final AuditLogWriter auditLogWriter;

    public ShopInfoServiceImpl(ShopInfoMapper shopMapper, AuditLogWriter auditLogWriter) {
        this.shopMapper = shopMapper;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public ShopInfoVO getPublic() {
        ShopInfo info = findConfig();
        if (info == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop information not configured");
        }
        return toView(info);
    }

    @Override
    public ShopInfoVO getForAdmin(AdminPrincipal actor) {
        requireAdministrator(actor);
        ShopInfo info = findConfig();
        return info == null ? null : toView(info);
    }

    @Override
    @Transactional
    public ShopInfoVO save(AdminPrincipal actor, ShopInfoRequest request) {
        requireAdministrator(actor);
        ShopInfo info = findConfig();
        if (info == null) {
            info = new ShopInfo();
            info.setConfigKey(CONFIG_KEY);
        }
        info.setName(request.name().trim());
        info.setSlogan(request.slogan().trim());
        info.setAddress(request.address().trim());
        info.setHours(request.hours().trim());
        info.setPhone(request.phone().trim());
        info.setWechat(request.wechat().trim());
        if (info.getId() == null) {
            shopMapper.insert(info);
        } else {
            shopMapper.updateById(info);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", info.getName());
        auditLogWriter.write(actor.id(), "SHOP_INFO_UPDATED", "shop_info", info.getId(), summary);
        return toView(shopMapper.selectById(info.getId()));
    }

    private ShopInfo findConfig() {
        return shopMapper.selectOne(new LambdaQueryWrapper<ShopInfo>()
                .eq(ShopInfo::getConfigKey, CONFIG_KEY));
    }

    private ShopInfoVO toView(ShopInfo info) {
        return new ShopInfoVO(info.getName(), info.getSlogan(), info.getAddress(),
                info.getHours(), info.getPhone(), info.getWechat());
    }

    private void requireAdministrator(AdminPrincipal actor) {
        if (!"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }
}
