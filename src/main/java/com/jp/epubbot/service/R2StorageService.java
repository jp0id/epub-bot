package com.jp.epubbot.service;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class R2StorageService {

    @Value("${cloud.r2.access-key}")
    private String accessKey;

    @Value("${cloud.r2.secret-key}")
    private String secretKey;

    @Value("${cloud.r2.account-id}")
    private String accountId;

    @Value("${cloud.r2.bucket-name}")
    private String bucketName;

    @Value("${cloud.r2.public-domain}")
    private String publicDomain;

    private AmazonS3 s3Client;

    @PostConstruct
    public void init() {
        String endpoint = String.format("https://%s.r2.cloudflarestorage.com", accountId);
        BasicAWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);

        s3Client = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, "auto"))
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();
    }

    public String uploadFile(String path, byte[] content, String contentType) {
        try {
            InputStream is = new ByteArrayInputStream(content);
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.length);
            metadata.setContentType(contentType);
            String key = path.startsWith("/") ? path.substring(1) : path;

            s3Client.putObject(new PutObjectRequest(bucketName, key, is, metadata));
            String baseUrl = publicDomain.endsWith("/")
                    ? publicDomain.substring(0, publicDomain.length() - 1)
                    : publicDomain;

            return baseUrl + "/" + key;

        } catch (Exception e) {
            log.error("R2 Upload Failed: {}", path, e);
            throw new RuntimeException("Upload failed", e);
        }
    }

    /**
     * @param prefix 文件夹路径，例如 "books/abc12345/"
     */
    public void deleteFolder(String prefix) {
        if (prefix == null || prefix.trim().isEmpty() || prefix.trim().equals("/")) {
            log.warn("拒绝删除空前缀或根目录，操作已取消");
            return;
        }

        String cleanPrefix = prefix.startsWith("/") ? prefix.substring(1) : prefix;

        if (!cleanPrefix.endsWith("/")) {
            cleanPrefix += "/";
        }

        log.info("开始删除 R2 前缀目录: {}", cleanPrefix);

        try {
            ObjectListing objectListing = null;

            do {
                ListObjectsRequest listRequest = new ListObjectsRequest()
                        .withBucketName(bucketName)
                        .withPrefix(cleanPrefix);

                if (objectListing != null) {
                    listRequest.setMarker(objectListing.getNextMarker());
                }

                objectListing = s3Client.listObjects(listRequest);
                List<S3ObjectSummary> summaries = objectListing.getObjectSummaries();

                if (summaries.isEmpty()) {
                    break;
                }

                List<DeleteObjectsRequest.KeyVersion> keys = new ArrayList<>();
                for (S3ObjectSummary summary : summaries) {
                    keys.add(new DeleteObjectsRequest.KeyVersion(summary.getKey()));
                }

                DeleteObjectsRequest deleteRequest = new DeleteObjectsRequest(bucketName)
                        .withKeys(keys)
                        .withQuiet(true);

                DeleteObjectsResult result = s3Client.deleteObjects(deleteRequest);
                log.info("已删除批次: {} 个文件 (前缀: {})", result.getDeletedObjects().size(), cleanPrefix);

            } while (objectListing.isTruncated());

            log.info("R2 目录删除完成: {}", cleanPrefix);

        } catch (Exception e) {
            log.error("删除 R2 目录失败: {}", cleanPrefix, e);
        }
    }
}