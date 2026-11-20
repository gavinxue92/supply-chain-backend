package dev.mattest.supplychain.service;

import dev.mattest.supplychain.dto.auth.CreateAccountRequest;
import dev.mattest.supplychain.dto.auth.ResetPasswordRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.auth.AccountVO;

import java.util.List;

public interface AdminAccountService {
    List<AccountVO> list();

    AccountVO create(AdminPrincipal actor, CreateAccountRequest request);

    void deactivate(AdminPrincipal actor, long id);

    void resetPassword(AdminPrincipal actor, long id, ResetPasswordRequest request);

    void updateNickname(AdminPrincipal actor, long id, String nickname);

    void hardDelete(AdminPrincipal actor, long id);
}
