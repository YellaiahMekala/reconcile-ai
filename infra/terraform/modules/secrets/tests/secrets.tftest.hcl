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
}

variables {
  name = "t"
}

run "anchor_keys_are_bare_base64_as_the_app_expects" {
  command = apply

  assert {
    condition     = !strcontains(jsondecode(aws_secretsmanager_secret_version.anchor.secret_string).private_key, "-----") && !strcontains(jsondecode(aws_secretsmanager_secret_version.anchor.secret_string).private_key, "\n")
    error_message = "The private key must be the bare base64 body (no PEM header, no newlines)."
  }

  assert {
    condition     = !strcontains(jsondecode(aws_secretsmanager_secret_version.anchor.secret_string).public_key, "-----") && !strcontains(jsondecode(aws_secretsmanager_secret_version.anchor.secret_string).public_key, "\n")
    error_message = "The public key must be the bare base64 body."
  }
}

run "database_passwords_are_long_and_distinct" {
  command = apply

  assert {
    condition     = length(jsondecode(aws_secretsmanager_secret_version.db.secret_string).owner_pass) >= 24 && length(jsondecode(aws_secretsmanager_secret_version.db.secret_string).app_password) >= 24
    error_message = "Generated passwords must be at least 24 characters."
  }

  assert {
    condition     = jsondecode(aws_secretsmanager_secret_version.db.secret_string).owner_pass != jsondecode(aws_secretsmanager_secret_version.db.secret_string).app_password
    error_message = "The owner and application logins must not share a password."
  }

  assert {
    condition     = jsondecode(aws_secretsmanager_secret_version.db.secret_string).app_user == "reconcile_app"
    error_message = "The application login name must match the Flyway placeholder default."
  }
}

run "openai_key_is_a_placeholder_never_a_real_value" {
  command = apply

  assert {
    condition     = startswith(aws_secretsmanager_secret_version.openai.secret_string, "REPLACE_ME")
    error_message = "Terraform must only create a placeholder; the real key is set with put-secret-value."
  }
}
