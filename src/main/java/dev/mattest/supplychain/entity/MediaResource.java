package dev.mattest.supplychain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("media_resources")
public class MediaResource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String objectKey;
    private String originalFilename;
    private String mediaType;
    private Long byteSize;
    private String resourceStatus;
    private String publicUrl;
    private Long uploadedBy;
    private LocalDateTime uploadExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "false", delval = "true")
    private Boolean isDeleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public Long getByteSize() { return byteSize; }
    public void setByteSize(Long byteSize) { this.byteSize = byteSize; }
    public String getResourceStatus() { return resourceStatus; }
    public void setResourceStatus(String resourceStatus) { this.resourceStatus = resourceStatus; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }
    public LocalDateTime getUploadExpiresAt() { return uploadExpiresAt; }
    public void setUploadExpiresAt(LocalDateTime uploadExpiresAt) { this.uploadExpiresAt = uploadExpiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean deleted) { isDeleted = deleted; }
}
