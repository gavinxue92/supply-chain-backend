package dev.mattest.supplychain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("shop_info")
public class ShopInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String configKey;
    private String name;
    private String slogan;
    private String address;
    private String hours;
    private String phone;
    private String wechat;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "false", delval = "true")
    private Boolean isDeleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlogan() { return slogan; }
    public void setSlogan(String slogan) { this.slogan = slogan; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getHours() { return hours; }
    public void setHours(String hours) { this.hours = hours; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getWechat() { return wechat; }
    public void setWechat(String wechat) { this.wechat = wechat; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean deleted) { isDeleted = deleted; }
}
