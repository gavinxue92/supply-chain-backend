package dev.mattest.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.mattest.supplychain.entity.MediaResource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MediaResourceMapper extends BaseMapper<MediaResource> {
    @Delete("DELETE FROM media_resources WHERE id = #{id}")
    int physicalDeleteById(@Param("id") long id);
}
