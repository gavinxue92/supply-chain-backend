package dev.mattest.supplychain.service;

import dev.mattest.supplychain.dto.contact.CustomerServiceRequest;
import dev.mattest.supplychain.dto.contact.CustomerServiceStatusRequest;
import dev.mattest.supplychain.entity.CustomerService;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.contact.CustomerServiceVO;

import java.util.List;

public interface CustomerServiceService {
    List<CustomerServiceVO> list();

    CustomerServiceVO create(AdminPrincipal actor, CustomerServiceRequest request);

    CustomerServiceVO update(AdminPrincipal actor, long id, CustomerServiceRequest request);

    void setActive(AdminPrincipal actor, long id, CustomerServiceStatusRequest request);

    void hardDelete(AdminPrincipal actor, long id);

    List<CustomerService> findActiveByIds(List<Long> ids);

    List<CustomerService> findActiveForDisplay(List<Long> ids);
}
