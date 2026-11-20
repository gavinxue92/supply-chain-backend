package dev.mattest.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.mattest.supplychain.entity.AdminSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdminSessionMapper extends BaseMapper<AdminSession> {
    @Delete("DELETE FROM admin_sessions WHERE admin_user_id = #{userId}")
    int physicalDeleteByUserId(@Param("userId") long userId);
}
