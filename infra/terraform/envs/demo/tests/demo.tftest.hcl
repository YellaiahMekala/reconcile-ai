# The whole demo stack wired together, against a mock AWS provider: checks the wiring between modules, not AWS itself.

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
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-south-1a", "ap-south-1b"] }
  }
  mock_data "aws_region" {
    defaults = { name = "ap-south-1" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
}

variables {
  allowed_cidrs = ["203.0.113.7/32"]
  budget_emails = ["owner@example.com"]
}

run "default_stack_plans_and_stays_closed_to_the_world" {
  command = apply

  assert {
    condition     = startswith(output.app_url, "http://")
    error_message = "Without a certificate the demo URL is plain HTTP."
  }

  assert {
    condition     = output.ci_deploy_role_arn == null
    error_message = "No CI roles are created until github_repository is set."
  }
}

run "github_repository_creates_the_ci_roles" {
  command = apply
  variables {
    github_repository = "OWNER/REPO"
    state_bucket      = "state-bucket"
  }

  assert {
    condition     = output.ci_deploy_role_arn != null && output.ci_plan_role_arn != null
    error_message = "Setting github_repository must create both CI roles."
  }
}

run "bedrock_profile_grants_only_that_model" {
  command = apply
  variables {
    chat_profile = "bedrock"
  }

  assert {
    condition     = output.cluster_name == "reconcile-demo"
    error_message = "Resource names should be prefixed reconcile-demo."
  }
}

run "unknown_chat_profile_is_rejected" {
  command = plan
  variables {
    chat_profile = "gpt5-in-the-basement"
  }
  expect_failures = [var.chat_profile]
}

run "world_open_allow_list_is_rejected" {
  command = plan
  variables {
    allowed_cidrs = ["0.0.0.0/0"]
  }
  expect_failures = [var.allowed_cidrs]
}
