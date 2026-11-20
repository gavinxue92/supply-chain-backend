package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.mattest.supplychain.dto.contact.CustomerServiceRequest;
import dev.mattest.supplychain.dto.contact.CustomerServiceStatusRequest;
import dev.mattest.supplychain.entity.CustomerService;
import dev.mattest.supplychain.entity.MediaResource;
import dev.mattest.supplychain.mapper.CustomerServiceMapper;
import dev.mattest.supplychain.mapper.MediaResourceMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.CustomerServiceService;
import dev.mattest.supplychain.service.MediaResourceService;
import dev.mattest.supplychain.vo.contact.CustomerServiceVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class CustomerServiceImpl implements CustomerServiceService {
    private final CustomerServiceMapper customerServiceMapper;
    private final MediaResourceMapper mediaResourceMapper;
    private final MediaResourceService mediaResourceService;
    private final AuditLogWriter auditLogWriter;

    public CustomerServiceImpl(
            CustomerServiceMapper customerServiceMapper,
            MediaResourceMapper mediaResourceMapper,
            MediaResourceService mediaResourceService,
            AuditLogWriter auditLogWriter) {
        this.customerServiceMapper = customerServiceMapper;
        this.mediaResourceMapper = mediaResourceMapper;
        this.mediaResourceService = mediaResourceService;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public List<CustomerServiceVO> list() {
        return customerServiceMapper.selectList(new LambdaQueryWrapper<CustomerService>()
                        .orderByAsc(CustomerService::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public CustomerServiceVO create(AdminPrincipal actor, CustomerServiceRequest request) {
        requireAdministrator(actor);
        CustomerService service = new CustomerService();
        applyRequest(service, request);
        service.setIsActive(true);
        customerServiceMapper.insert(service);
        auditLogWriter.write(actor.id(), "CUSTOMER_SERVICE_CREATED", "customer_services", service.getId(),
                auditSummary(service));
        return toView(customerServiceMapper.selectById(service.getId()));
    }

    @Override
    @Transactional
    public CustomerServiceVO update(AdminPrincipal actor, long id, CustomerServiceRequest request) {
        requireAdministrator(actor);
        CustomerService service = find(id);
        Long previousQrMediaId = service.getQrMediaId();
        applyRequest(service, request);
        customerServiceMapper.updateById(service);
        if (previousQrMediaId != null && !previousQrMediaId.equals(service.getQrMediaId())) {
            mediaResourceService.cleanupUnreferenced(actor, List.of(previousQrMediaId));
        }
        auditLogWriter.write(actor.id(), "CUSTOMER_SERVICE_UPDATED", "customer_services", id,
                auditSummary(service));
        return toView(customerServiceMapper.selectById(id));
    }

    @Override
    @Transactional
    public void setActive(AdminPrincipal actor, long id, CustomerServiceStatusRequest request) {
        requireAdministrator(actor);
        CustomerService service = find(id);
        service.setIsActive(request.active());
        customerServiceMapper.updateById(service);
        auditLogWriter.write(actor.id(),
                request.active() ? "CUSTOMER_SERVICE_UPDATED" : "CUSTOMER_SERVICE_DEACTIVATED",
                "customer_services", id, auditSummary(service));
    }

    @Override
    @Transactional
    public void hardDelete(AdminPrincipal actor, long id) {
        requireAdministrator(actor);
        CustomerService service = find(id);
        auditLogWriter.write(actor.id(), "CUSTOMER_SERVICE_DELETED", "customer_services", id,
                auditSummary(service));
        customerServiceMapper.physicalDeleteById(id);
        if (service.getQrMediaId() != null) {
            mediaResourceService.cleanupUnreferenced(actor, List.of(service.getQrMediaId()));
        }
    }

    @Override
    public List<CustomerService> findActiveByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer services must not repeat");
        }
        List<CustomerService> services = customerServiceMapper.selectList(new LambdaQueryWrapper<CustomerService>()
                .in(CustomerService::getId, ids));
        List<CustomerService> ordered = new ArrayList<>();
        for (Long id : ids) {
            CustomerService service = services.stream()
                    .filter(candidate -> candidate.getId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Customer service does not exist"));
            if (!Boolean.TRUE.equals(service.getIsActive())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Only active customer services can be selected");
            }
            ordered.add(service);
        }
        return ordered;
    }

    @Override
    public List<CustomerService> findActiveForDisplay(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<CustomerService> services = customerServiceMapper.selectList(new LambdaQueryWrapper<CustomerService>()
                .in(CustomerService::getId, ids));
        List<CustomerService> ordered = new ArrayList<>();
        for (Long id : ids) {
            services.stream()
                    .filter(service -> service.getId().equals(id)
                            && Boolean.TRUE.equals(service.getIsActive()))
                    .findFirst()
                    .ifPresent(ordered::add);
        }
        return ordered;
    }

    private CustomerService find(long id) {
        CustomerService service = customerServiceMapper.selectById(id);
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer service not found");
        }
        return service;
    }

    private void applyRequest(CustomerService service, CustomerServiceRequest request) {
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        if (displayName == null || displayName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer service name is required");
        }
        String wechatId = normalize(request.wechatId());
        String phone = normalize(request.phone());
        Long qrMediaId = request.qrMediaId();
        if (wechatId == null && phone == null && qrMediaId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least one WeChat ID, QR code, or phone number is required");
        }
        validateQrMedia(qrMediaId);
        service.setDisplayName(displayName);
        service.setWechatId(wechatId);
        service.setPhone(phone);
        service.setQrMediaId(qrMediaId);
    }

    private void validateQrMedia(Long mediaId) {
        if (mediaId == null) {
            return;
        }
        long count = mediaResourceMapper.selectCount(new LambdaQueryWrapper<MediaResource>()
                .eq(MediaResource::getId, mediaId)
                .eq(MediaResource::getResourceStatus, "READY"));
        if (count != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The customer-service QR code must reference confirmed media");
        }
    }

    private CustomerServiceVO toView(CustomerService service) {
        return new CustomerServiceVO(
                service.getId(),
                service.getDisplayName(),
                service.getWechatId(),
                service.getQrMediaId(),
                service.getQrMediaId() == null ? null : "/api/v1/media/" + service.getQrMediaId(),
                service.getPhone(),
                Boolean.TRUE.equals(service.getIsActive()));
    }

    private Map<String, Object> auditSummary(CustomerService service) {
        return Map.of(
                "displayName", service.getDisplayName(),
                "hasWechatId", service.getWechatId() != null,
                "hasQrCode", service.getQrMediaId() != null,
                "hasPhone", service.getPhone() != null,
                "active", Boolean.TRUE.equals(service.getIsActive()));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void requireAdministrator(AdminPrincipal actor) {
        if (!"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }
}
