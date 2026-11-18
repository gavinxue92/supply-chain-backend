package dev.mattest.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.mattest.supplychain.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
    @Select("SELECT COUNT(*) FROM categories WHERE parent_id = #{categoryId}")
    long countChildrenIncludingDeleted(@Param("categoryId") long categoryId);

    @Select("SELECT COUNT(*) FROM products WHERE category_id = #{categoryId}")
    long countProductsIncludingArchived(@Param("categoryId") long categoryId);
}
