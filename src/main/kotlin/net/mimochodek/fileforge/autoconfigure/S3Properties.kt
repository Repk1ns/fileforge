package net.mimochodek.fileforge.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * S3 connection and layout configuration, bound to `fileforge.s3`.
 *
 * Only genuinely S3-specific settings live here. Unlike 1.x, none of these is
 * an activation trigger and none of them is mandatory except [bucket]:
 *
 * - [endpoint] is **optional** - omit it to talk to real AWS S3. In 1.x it was
 *   the activation trigger, so using AWS meant inventing a fake endpoint.
 * - [accessKey] / [secretKey] are **optional** - omit them to use the AWS
 *   default credential chain, which is what makes IAM roles, instance profiles,
 *   SSO and environment credentials work. 1.x always forced static credentials,
 *   a hard blocker on ECS/EKS/EC2.
 * - [pathStyleAccess] is configurable; 1.x hardcoded it to `true`.
 */
@ConfigurationProperties(prefix = "fileforge.s3")
public class S3Properties(
    public val bucket: String = "",
    public val region: String = "us-east-1",
    public val endpoint: URI? = null,
    public val accessKey: String? = null,
    public val secretKey: String? = null,
    /** `true` suits self-hosted gateways; set `false` for real AWS S3. */
    public val pathStyleAccess: Boolean = true,
) {
    init {
        require(bucket.isNotBlank()) {
            "fileforge.s3.bucket must be configured (set fileforge.provider to something " +
                "other than 's3' to disable FileForge)"
        }
        require(region.isNotBlank()) { "fileforge.s3.region must not be blank" }
        require(accessKey.isNullOrBlank() == secretKey.isNullOrBlank()) {
            "fileforge.s3.access-key and fileforge.s3.secret-key must be set together; " +
                "omit both to use the AWS default credential chain"
        }
    }

    /** `true` when static credentials were supplied instead of the default chain. */
    public val hasStaticCredentials: Boolean
        get() = !accessKey.isNullOrBlank() && !secretKey.isNullOrBlank()
}
