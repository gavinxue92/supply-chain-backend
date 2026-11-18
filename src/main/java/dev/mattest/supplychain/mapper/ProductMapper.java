package dev.mattest.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.mattest.supplychain.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
