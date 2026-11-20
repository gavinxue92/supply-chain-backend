package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.mattest.supplychain.dto.contact.GlobalContactRequest;
import dev.mattest.supplychain.entity.CustomerService;
import dev.mattest.supplychain.entity.GlobalContactConfig;
import dev.mattest.supplychain.entity.GlobalContactServiceLink;
import dev.mattest.supplychain.mapper.CustomerServiceMapper;
import dev.mattest.supplychain.mapper.GlobalContactConfigMapper;
import dev.mattest.supplychain.mapper.GlobalContactServiceLinkMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.CustomerServiceService;
import dev.mattest.supplychain.service.GlobalContactService;
import dev.mattest.supplychain.vo.contact.CustomerServiceVO;
import dev.mattest.supplychain.vo.contact.GlobalContactVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class GlobalContactServiceImpl implements GlobalContactService {
    private static final String CONFIG_KEY = "GLOBAL";

    private final GlobalContactConfigMapper configMapper;
    private final GlobalContactServiceLinkMapper linkMapper;
    private final CustomerServiceService customerServiceService;
    private final CustomerServiceMapper customerServiceMapper;
    private final AuditLogWriter auditLogWriter;

    public GlobalContactServiceImpl(
            GlobalContactConfigMapper configMapper,
            GlobalContactServiceLinkMapper linkMapper,
            CustomerServiceService customerServiceService,
            CustomerServiceMapper customerServiceMapper,
            AuditLogWriter auditLogWriter) {
        this.configMapper = configMapper;
        this.linkMapper = linkMapper;
        this.customerServiceService = customerServiceService;
        this.customerServiceMapper = customerServiceMapper;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public GlobalContactVO get(AdminPrincipal actor) {
        requireAdministrator(actor);
        GlobalContactConfig config = findConfig();
        return config == null ? null : toView(config);
    }

    @Override
    @Transactional
    public GlobalContactVO save(AdminPrincipal actor, GlobalContactRequest request) {
        requireAdministrator(actor);
        List<Long> serviceIds = request.serviceIds();
        if (serviceIds == null || serviceIds.isEmpty()
                || new LinkedHashSet<>(serviceIds).size() != serviceIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Global contact requires a non-empty list of distinct customer services");
        }
        customerServiceService.findActiveByIds(serviceIds);

        GlobalContactConfig config = findConfig();
        if (config == null) {
            config = new GlobalContactConfig();
            config.setConfigKey(CONFIG_KEY);
            configMapper.insert(config);
        }
        linkMapper.delete(new LambdaQueryWrapper<GlobalContactServiceLink>()
                .eq(GlobalContactServiceLink::getGlobalConfigId, config.getId()));
        for (int index = 0; index < serviceIds.size(); index++) {
            GlobalContactServiceLink link = new GlobalContactServiceLink();
            link.setGlobalConfigId(config.getId());
            link.setCustomerServiceId(serviceIds.get(index));
            link.setSortOrder(index);
            linkMapper.insert(link);
        }
        auditLogWriter.write(actor.id(), "GLOBAL_CONTACT_SERVICES_UPDATED",
                "global_contact_configs", config.getId(), Map.of("serviceCount", serviceIds.size()));
        return toView(configMapper.selectById(config.getId()));
    }

    private GlobalContactConfig findConfig() {
        return configMapper.selectOne(new LambdaQueryWrapper<GlobalContactConfig>()
                .eq(GlobalContactConfig::getConfigKey, CONFIG_KEY));
    }

    private GlobalContactVO toView(GlobalContactConfig config) {
        List<CustomerServiceVO> services = linkMapper.selectList(new LambdaQueryWrapper<GlobalContactServiceLink>()
                        .eq(GlobalContactServiceLink::getGlobalConfigId, config.getId())
                        .orderByAsc(GlobalContactServiceLink::getSortOrder)
                        .orderByAsc(GlobalContactServiceLink::getId))
                .stream()
                .map(link -> customerServiceMapper.selectById(link.getCustomerServiceId()))
                .filter(service -> service != null && Boolean.TRUE.equals(service.getIsActive()))
                .map(this::toCustomerServiceView)
                .toList();
        return new GlobalContactVO(config.getId(), services);
    }

    private CustomerServiceVO toCustomerServiceView(CustomerService service) {
        return new CustomerServiceVO(
                service.getId(),
                service.getDisplayName(),
                service.getWechatId(),
                service.getQrMediaId(),
                service.getQrMediaId() == null ? null : "/api/v1/media/" + service.getQrMediaId(),
                service.getPhone(),
                Boolean.TRUE.equals(service.getIsActive()));
    }

    private void requireAdministrator(AdminPrincipal actor) {
        if (!"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }
}
