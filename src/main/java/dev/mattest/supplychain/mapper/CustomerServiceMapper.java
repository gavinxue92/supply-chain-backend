package dev.mattest.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.mattest.supplychain.entity.CustomerService;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerServiceMapper extends BaseMapper<CustomerService> {
    @Delete("DELETE FROM customer_services WHERE id = #{id}")
    int physicalDeleteById(@Param("id") long id);
}
