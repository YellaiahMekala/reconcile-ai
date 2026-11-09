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
  name               = "t"
  plan_subjects      = ["repo:OWNER/REPO:pull_request"]
  deploy_subjects    = ["repo:OWNER/REPO:environment:aws-demo"]
  state_bucket_arn   = "arn:aws:s3:::state-bucket"
  ecr_repository_arn = "arn:aws:ecr:ap-south-1:123456789012:repository/t"
  ecs_service_arn    = "arn:aws:ecs:ap-south-1:123456789012:service/t/t"
  passable_role_arns = ["arn:aws:iam::123456789012:role/t-task", "arn:aws:iam::123456789012:role/t-task-execution"]
}

run "deploy_role_trusts_only_the_named_environment" {
  command = apply

  assert {
    condition     = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringLike["token.actions.githubusercontent.com:sub"] == ["repo:OWNER/REPO:environment:aws-demo"]
    error_message = "The deploy role must trust exactly the aws-demo environment of one repository."
  }

  assert {
    condition     = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "The token audience must be checked."
  }
}

run "wildcard_deploy_subject_is_rejected" {
  command = plan
  variables {
    deploy_subjects = ["repo:OWNER/REPO:*"]
  }
  expect_failures = [var.deploy_subjects]
}

run "deploy_role_can_roll_out_an_image_but_not_change_infrastructure" {
  command = apply

  assert {
    condition = !anytrue([
      for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      anytrue([for a in flatten([s.Action]) : can(regex("^(ec2|rds|s3|kms|secretsmanager|iam:(Create|Put|Attach|Delete)|elasticloadbalancing)", a))])
    ])
    error_message = "The deploy role must not be able to create or change infrastructure, secrets or data."
  }

  assert {
    condition     = one([for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Condition.StringEquals["iam:PassedToService"] if s.Sid == "HandRolesToEcs"]) == "ecs-tasks.amazonaws.com"
    error_message = "PassRole must be limited to ECS tasks."
  }
}

run "plan_role_is_read_only_plus_state_access" {
  command = apply

  assert {
    condition     = aws_iam_role_policy_attachment.plan_readonly.policy_arn == "arn:aws:iam::aws:policy/ReadOnlyAccess"
    error_message = "The plan role must be the AWS ReadOnlyAccess policy."
  }
}

run "existing_oidc_provider_can_be_reused" {
  command = apply
  variables {
    create_oidc_provider       = false
    existing_oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
  }

  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 0
    error_message = "No second OIDC provider may be created when one exists."
  }
}
