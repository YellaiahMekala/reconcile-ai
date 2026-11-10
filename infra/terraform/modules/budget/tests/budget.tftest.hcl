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

run "budget_alerts_at_50_80_actual_and_100_forecast" {
  command = apply
  variables {
    name         = "t"
    alert_emails = ["owner@example.com"]
  }

  assert {
    condition     = aws_budgets_budget.monthly.limit_amount == "25" && aws_budgets_budget.monthly.time_unit == "MONTHLY"
    error_message = "Default budget is 25 USD per month."
  }

  assert {
    condition     = length(aws_budgets_budget.monthly.notification) == 3
    error_message = "Expected three notifications."
  }

  assert {
    condition     = contains([for n in aws_budgets_budget.monthly.notification : "${n.notification_type}:${n.threshold}"], "FORECASTED:100")
    error_message = "A forecast alert at 100% is required: it warns before the money is spent."
  }
}

run "budget_without_recipients_is_rejected" {
  command = plan
  variables {
    name         = "t"
    alert_emails = []
  }
  expect_failures = [var.alert_emails]
}
