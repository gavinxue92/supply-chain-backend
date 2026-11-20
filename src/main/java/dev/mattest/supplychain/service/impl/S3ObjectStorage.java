package dev.mattest.supplychain.service.impl;

import dev.mattest.supplychain.config.MediaStorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Component
public class S3ObjectStorage {
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final MediaStorageProperties properties;

    public S3ObjectStorage(
            S3Client mediaS3Client,
            S3Presigner mediaS3Presigner,
            MediaStorageProperties properties) {
        this.s3Client = mediaS3Client;
        this.presigner = mediaS3Presigner;
        this.properties = properties;
    }

    public String newObjectKey(String prefix) {
        return prefix + "/" + UUID.randomUUID();
    }

    public String presignPut(String objectKey, String contentType) {
        ensureBucket();
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getUploadExpirationSeconds()))
                .putObjectRequest(putRequest)
                .build();
        PresignedPutObjectRequest signedRequest = presigner.presignPutObject(presignRequest);
        return signedRequest.url().toExternalForm();
    }

    public long objectSize(String objectKey) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build()).contentLength();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new MissingUploadedObjectException(exception);
            }
            throw exception;
        }
    }

    public byte[] readPendingObject(String objectKey, int maximumBytes) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .build();
        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request)) {
            return stream.readNBytes(maximumBytes + 1);
        } catch (NoSuchKeyException exception) {
            throw new MissingUploadedObjectException(exception);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new MissingUploadedObjectException(exception);
            }
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read uploaded media object", exception);
        }
    }

    public void storeConfirmedObject(String objectKey, String contentType, byte[] bytes) {
        ensureBucket();
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    public void deleteObject(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
        }
    }

    public byte[] readConfirmedObject(String objectKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .build();
        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request)) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read confirmed media object", exception);
        }
    }

    private void ensureBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
            } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
                // Another application instance created this bucket after the head request.
            }
        }
    }

    public String publicUrl(long mediaId) {
        return properties.getPublicBaseUrl().replaceAll("/+$", "") + "/api/v1/media/" + mediaId;
    }

    public long uploadExpirationSeconds() {
        return properties.getUploadExpirationSeconds();
    }

    public static final class MissingUploadedObjectException extends RuntimeException {
        public MissingUploadedObjectException(Throwable cause) {
            super(cause);
        }
    }
}
