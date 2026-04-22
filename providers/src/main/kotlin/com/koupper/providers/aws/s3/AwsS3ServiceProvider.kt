package com.koupper.providers.aws.s3

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class AwsS3ServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(S3Client::class, { S3ClientImpl() })
    }
}