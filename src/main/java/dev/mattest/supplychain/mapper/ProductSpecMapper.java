package dev.mattest.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.mattest.supplychain.entity.ProductSpec;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductSpecMapper extends BaseMapper<ProductSpec> {
}
