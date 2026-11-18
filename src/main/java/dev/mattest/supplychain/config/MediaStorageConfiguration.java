package dev.mattest.supplychain.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(MediaStorageProperties.class)
public class MediaStorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client mediaS3Client(MediaStorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentialsProvider(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner mediaS3Presigner(MediaStorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.getPublicEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentialsProvider(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private AwsCredentialsProvider credentialsProvider(MediaStorageProperties properties) {
        if (properties.getAccessKey().isBlank() && properties.getSecretKey().isBlank()) {
            return DefaultCredentialsProvider.create();
        }
        if (properties.getAccessKey().isBlank() || properties.getSecretKey().isBlank()) {
            throw new IllegalStateException("Both media storage access key and secret key must be configured");
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
    }
}
