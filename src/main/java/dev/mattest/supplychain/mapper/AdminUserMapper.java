package dev.mattest.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.mattest.supplychain.entity.AdminUser;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
    @Update("""
            UPDATE admin_users
            SET locked_until = CASE
                    WHEN failed_login_attempts + 1 >= #{maximumAttempts}
                    THEN #{lockedUntil} ELSE locked_until END,
                failed_login_attempts = failed_login_attempts + 1
            WHERE id = #{userId} AND is_active = TRUE AND is_deleted = FALSE
            """)
    int registerFailedLogin(
            @Param("userId") long userId,
            @Param("maximumAttempts") int maximumAttempts,
            @Param("lockedUntil") LocalDateTime lockedUntil);

    @Delete("DELETE FROM admin_users WHERE id = #{id}")
    int physicalDeleteById(@Param("id") long id);
}
