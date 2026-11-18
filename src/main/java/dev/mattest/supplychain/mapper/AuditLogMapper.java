package dev.mattest.supplychain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.mattest.supplychain.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
