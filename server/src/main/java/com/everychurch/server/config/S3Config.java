package com.everychurch.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Credentials come from the AWS SDK's standard default chain (AWS_ACCESS_KEY_ID /
 * AWS_SECRET_ACCESS_KEY / AWS_REGION env vars, or an instance profile in real
 * deployments) — nothing custom wired here. Building this bean never contacts AWS
 * (region resolution is local), so app startup doesn't require real credentials;
 * only actually presigning a URL does (see S3PresignService).
 */
@Configuration
public class S3Config {

    @Bean
    public S3Presigner s3Presigner(@Value("${aws.region:us-east-1}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }
}
