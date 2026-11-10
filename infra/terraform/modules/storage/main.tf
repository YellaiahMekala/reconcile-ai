# S3 bucket for audit-chain anchors: Object Lock (WORM), versioned, KMS-encrypted, TLS-only, never public.
#
# The app signs the audit chain head every 15 minutes and writes it here with a retention date. While that retention
# runs, nobody (including an admin) can delete or overwrite the object in COMPLIANCE mode. The demo uses GOVERNANCE with
# a 1-day retention so `terraform destroy` can still clean up; production keeps COMPLIANCE and 365 days.

data "aws_caller_identity" "current" {}

locals {
  bucket_name = var.bucket_name != "" ? var.bucket_name : "${var.name}-audit-anchors-${data.aws_caller_identity.current.account_id}"
}

resource "aws_kms_key" "anchors" {
  description             = "Encrypts the ${var.name} audit anchor bucket"
  enable_key_rotation     = true
  deletion_window_in_days = 7

  # Explicit policy: the account (and so IAM) administers and uses the key. The app's task role gets its kms:
  # permissions through its own IAM policy, which this statement lets take effect.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AccountAdministersViaIam"
      Effect    = "Allow"
      Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
      Action    = "kms:*"
      Resource  = "*"
    }]
  })
}

resource "aws_kms_alias" "anchors" {
  name          = "alias/${var.name}-audit-anchors"
  target_key_id = aws_kms_key.anchors.key_id
}

resource "aws_s3_bucket" "anchors" {
  #checkov:skip=CKV_AWS_18: The bucket is itself the audit trail (WORM, versioned); server access logging would need a second bucket. Add for production.
  #checkov:skip=CKV2_AWS_62: No consumer of bucket events in this design.
  #checkov:skip=CKV_AWS_144: Cross-region replication is a production follow-up; not needed for a demo.
  bucket              = local.bucket_name
  object_lock_enabled = true
  force_destroy       = var.force_destroy
}

resource "aws_s3_bucket_versioning" "anchors" {
  bucket = aws_s3_bucket.anchors.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "anchors" {
  bucket = aws_s3_bucket.anchors.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.anchors.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "anchors" {
  bucket                  = aws_s3_bucket.anchors.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_object_lock_configuration" "anchors" {
  bucket              = aws_s3_bucket.anchors.id
  object_lock_enabled = "Enabled"
  rule {
    default_retention {
      mode = var.lock_mode
      days = var.retention_days
    }
  }
  depends_on = [aws_s3_bucket_versioning.anchors]
}

resource "aws_s3_bucket_lifecycle_configuration" "anchors" {
  bucket = aws_s3_bucket.anchors.id
  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
  depends_on = [aws_s3_bucket_versioning.anchors]
}

resource "aws_s3_bucket_policy" "anchors" {
  bucket = aws_s3_bucket.anchors.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.anchors.arn, "${aws_s3_bucket.anchors.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.anchors]
}
