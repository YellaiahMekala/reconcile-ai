mock_provider "aws" {
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/mock" }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:ap-south-1:123456789012:log-group:/mock" }
  }
  mock_resource "aws_kms_key" {
    defaults = { arn = "arn:aws:kms:ap-south-1:123456789012:key/00000000-0000-0000-0000-000000000000" }
  }
  mock_resource "aws_ecr_repository" {
    defaults = { arn = "arn:aws:ecr:ap-south-1:123456789012:repository/mock", repository_url = "123456789012.dkr.ecr.ap-south-1.amazonaws.com/mock" }
  }
  mock_resource "aws_ecs_service" {
    defaults = { id = "arn:aws:ecs:ap-south-1:123456789012:service/mock/mock" }
  }
  mock_resource "aws_lb" {
    defaults = { arn = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:loadbalancer/app/mock/0123456789abcdef", dns_name = "mock-1234.ap-south-1.elb.amazonaws.com" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:targetgroup/mock/0123456789abcdef" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::mock-bucket" }
  }
  mock_resource "aws_secretsmanager_secret" {
    defaults = { arn = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:mock-AbCdEf" }
  }
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
}

variables {
  name = "t"
}

run "anchor_bucket_is_worm_encrypted_and_private" {
  command = apply

  assert {
    condition     = aws_s3_bucket.anchors.object_lock_enabled == true
    error_message = "Object Lock must be on: the anchors are the tamper evidence."
  }

  assert {
    condition     = aws_s3_bucket.anchors.bucket == "t-audit-anchors-123456789012"
    error_message = "Default bucket name should include the account id so it is unique."
  }

  assert {
    condition     = aws_s3_bucket_versioning.anchors.versioning_configuration[0].status == "Enabled"
    error_message = "Object Lock needs versioning."
  }

  assert {
    condition = (
      aws_s3_bucket_public_access_block.anchors.block_public_acls &&
      aws_s3_bucket_public_access_block.anchors.block_public_policy &&
      aws_s3_bucket_public_access_block.anchors.ignore_public_acls &&
      aws_s3_bucket_public_access_block.anchors.restrict_public_buckets
    )
    error_message = "All four public-access blocks must be on."
  }

  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.anchors.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "aws:kms"
    error_message = "Anchors must be encrypted with a KMS key."
  }

  assert {
    condition     = aws_kms_key.anchors.enable_key_rotation == true
    error_message = "Key rotation must be on."
  }
}

run "plain_http_is_denied_by_policy" {
  command = apply

  assert {
    condition     = jsondecode(aws_s3_bucket_policy.anchors.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false"
    error_message = "The bucket policy must deny requests that are not over TLS."
  }
}

run "demo_default_is_governance_one_day" {
  command = apply

  assert {
    condition     = one(aws_s3_bucket_object_lock_configuration.anchors.rule).default_retention[0].mode == "GOVERNANCE" && one(aws_s3_bucket_object_lock_configuration.anchors.rule).default_retention[0].days == 1
    error_message = "The demo must default to GOVERNANCE, 1 day, so destroy can clean up."
  }
}

run "compliance_mode_is_accepted_and_unknown_mode_rejected" {
  command = plan
  variables {
    lock_mode = "COMPLIANCE"
  }
  assert {
    condition     = one(aws_s3_bucket_object_lock_configuration.anchors.rule).default_retention[0].mode == "COMPLIANCE"
    error_message = "COMPLIANCE must pass through unchanged."
  }
}

run "bad_lock_mode_is_rejected" {
  command = plan
  variables {
    lock_mode = "SOMETIMES"
  }
  expect_failures = [var.lock_mode]
}
