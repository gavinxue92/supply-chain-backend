package dev.mattest.supplychain.vo.contact;

import java.util.List;

public record GlobalContactVO(long id, List<CustomerServiceVO> services) {
}
