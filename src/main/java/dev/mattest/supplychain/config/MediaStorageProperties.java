package dev.mattest.supplychain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.media.storage")
public class MediaStorageProperties {
    private String endpoint = "http://localhost:9000";
    private String publicEndpoint = "http://localhost:9000";
    private String accessKey = "";
    private String secretKey = "";
    private String region = "us-east-1";
    private String bucket = "product-media";
    private long uploadExpirationSeconds = 300;
    private String publicBaseUrl = "http://localhost:8080";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getPublicEndpoint() { return publicEndpoint; }
    public void setPublicEndpoint(String publicEndpoint) { this.publicEndpoint = publicEndpoint; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public long getUploadExpirationSeconds() { return uploadExpirationSeconds; }
    public void setUploadExpirationSeconds(long uploadExpirationSeconds) {
        this.uploadExpirationSeconds = uploadExpirationSeconds;
    }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
}
