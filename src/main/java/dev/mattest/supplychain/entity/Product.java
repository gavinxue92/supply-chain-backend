package dev.mattest.supplychain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("products")
public class Product {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String status;
    private Long categoryId;
    private Long mainImageMediaId;
    private String detailHtml;
    private String contactMode;
    private String contactWechatId;
    private Long contactQrMediaId;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Boolean isDeleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Long getMainImageMediaId() { return mainImageMediaId; }
    public void setMainImageMediaId(Long mainImageMediaId) { this.mainImageMediaId = mainImageMediaId; }
    public String getDetailHtml() { return detailHtml; }
    public void setDetailHtml(String detailHtml) { this.detailHtml = detailHtml; }
    public String getContactMode() { return contactMode; }
    public void setContactMode(String contactMode) { this.contactMode = contactMode; }
    public String getContactWechatId() { return contactWechatId; }
    public void setContactWechatId(String contactWechatId) { this.contactWechatId = contactWechatId; }
    public Long getContactQrMediaId() { return contactQrMediaId; }
    public void setContactQrMediaId(Long contactQrMediaId) { this.contactQrMediaId = contactQrMediaId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean deleted) { isDeleted = deleted; }
}
