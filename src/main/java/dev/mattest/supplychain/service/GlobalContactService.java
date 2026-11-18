package dev.mattest.supplychain.service;

import dev.mattest.supplychain.dto.contact.GlobalContactRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.contact.GlobalContactVO;

public interface GlobalContactService {
    GlobalContactVO get(AdminPrincipal actor);
    GlobalContactVO save(AdminPrincipal actor, GlobalContactRequest request);
}
