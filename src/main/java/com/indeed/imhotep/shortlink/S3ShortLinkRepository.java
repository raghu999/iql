/*
 * Copyright (C) 2017 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.indeed.imhotep.shortlink;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.Region;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.springframework.core.env.PropertyResolver;

import java.io.IOException;
import java.io.InputStream;

/**
 * Backing store for {@link ShortLinkRepository} that uses an AWS S3 bucket
 */
public class S3ShortLinkRepository implements ShortLinkRepository {
    static final Logger log = Logger.getLogger(S3ShortLinkRepository.class);

    // Start all shortlink objects with a prefix
    private static final String OBJECT_PREFIX = "shortlinks/";

    private boolean enabled;
    private AmazonS3Client client;
    private String bucket;

    public S3ShortLinkRepository(final PropertyResolver props) {
        String awsRegion;
        String awsKey;
        String awsSecret;

        enabled = true;
        try {
            bucket = props.getProperty("shortlink.s3.bucket", String.class);
            awsKey = props.getProperty("shortlink.s3.s3key", String.class);
            awsSecret = props.getProperty("shortlink.s3.s3secret", String.class);
            if (awsKey == null || awsSecret == null) {
                log.warn("No AWS key or Secret found.  Using Anonymous access.");
                client = new AmazonS3Client();
            } else {
                client = new AmazonS3Client(new BasicAWSCredentials(awsKey, awsSecret));
            }

            boolean exists = client.doesBucketExist(bucket);
            if (! exists) {
                awsRegion = props.getProperty("aws.s3.region",
                                              String.class,
                                              Region.US_Standard.toString());
                client.createBucket(bucket, awsRegion);
            }
        } catch (Exception e) {
            log.info("Failed to initialize the S3 client. Shortlinking disabled.", e);
            enabled = false;
        }
    }

    @Override
    public boolean mapShortCode(String code, String query) throws IOException {
        if(!enabled) {
            throw new IllegalStateException("Shortlink feature disabled");
        }

        if (client.doesObjectExist(bucket, OBJECT_PREFIX + code)) {
            return false;
        }

        client.putObject(bucket, OBJECT_PREFIX + code, query);
        return true;
    }

    @Override
    public String resolveShortCode(String shortCode) throws IOException {
        if(!enabled) {
            throw new IllegalStateException("Shortlink feature disabled");
        }

        final InputStream contents = client.getObject(bucket, OBJECT_PREFIX + shortCode).getObjectContent();
        final String query = IOUtils.toString(contents, "UTF-8");
        contents.close();
        return query;
    }
}


