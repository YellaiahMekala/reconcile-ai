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
  mock_data "aws_region" {
    defaults = { name = "ap-south-1" }
  }
}

variables {
  name                  = "t"
  vpc_id                = "vpc-0123456789abcdef0"
  public_subnet_ids     = ["subnet-pub1", "subnet-pub2"]
  private_subnet_ids    = ["subnet-prv1", "subnet-prv2"]
  app_security_group_id = "sg-0123456789abcdef0"
  allowed_cidrs         = ["203.0.113.7/32"]
  db_secret_arn         = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:t/db-AbCdEf"
  anchor_secret_arn     = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:t/anchor-AbCdEf"
  openai_secret_arn     = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:t/openai-AbCdEf"
  anchor_bucket_arn     = "arn:aws:s3:::t-anchors"
  anchor_kms_key_arn    = "arn:aws:kms:ap-south-1:123456789012:key/00000000-0000-0000-0000-000000000000"
  environment           = { SPRING_PROFILES_ACTIVE = "mock-llm", AUDIT_ANCHOR_ENABLED = "true" }
}

run "world_open_load_balancer_is_refused" {
  command = plan
  variables {
    allowed_cidrs = ["0.0.0.0/0"]
  }
  expect_failures = [var.allowed_cidrs]
}

run "empty_allow_list_is_refused" {
  command = plan
  variables {
    allowed_cidrs = []
  }
  expect_failures = [var.allowed_cidrs]
}

run "load_balancer_ingress_is_exactly_the_allow_list" {
  command = apply

  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.alb_in) == 1 && aws_vpc_security_group_ingress_rule.alb_in["203.0.113.7/32"].cidr_ipv4 == "203.0.113.7/32"
    error_message = "Only the allowed CIDR may reach the load balancer."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.alb_in["203.0.113.7/32"].from_port == 80
    error_message = "Without a certificate the demo listens on 80."
  }

  assert {
    condition     = length(aws_lb_listener.http) == 1 && length(aws_lb_listener.https) == 0
    error_message = "No certificate means one HTTP listener and no HTTPS listener."
  }
}

run "certificate_switches_to_https" {
  command = apply
  variables {
    certificate_arn = "arn:aws:acm:ap-south-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
  }

  assert {
    condition     = length(aws_lb_listener.https) == 1 && length(aws_lb_listener.http) == 0
    error_message = "A certificate must give HTTPS only, with no plain HTTP listener."
  }

  assert {
    condition     = aws_lb_listener.https[0].ssl_policy == "ELBSecurityPolicy-TLS13-1-2-2021-06"
    error_message = "Use a modern TLS policy."
  }
}

run "app_port_is_open_to_the_load_balancer_only" {
  command = apply

  assert {
    condition     = aws_vpc_security_group_ingress_rule.app_from_alb.from_port == 8080 && aws_vpc_security_group_ingress_rule.app_from_alb.cidr_ipv4 == null
    error_message = "Port 8080 must be reachable from the load balancer security group only, never from a CIDR."
  }
}

run "task_role_has_no_wildcard_actions_and_no_bedrock_by_default" {
  command = apply

  assert {
    condition     = !anytrue([for s in jsondecode(aws_iam_role_policy.task.policy).Statement : anytrue([for a in flatten([s.Action]) : a == "*" || endswith(a, ":*")])])
    error_message = "The task role must not contain wildcard actions."
  }

  assert {
    condition     = !anytrue([for s in jsondecode(aws_iam_role_policy.task.policy).Statement : s.Sid == "InvokeApprovedBedrockModels"])
    error_message = "With no model ids the task must have no Bedrock permission at all."
  }
}

run "bedrock_access_is_scoped_to_the_named_model" {
  command = apply
  variables {
    bedrock_model_ids = ["amazon.nova-lite-v1:0"]
  }

  assert {
    condition     = one([for s in jsondecode(aws_iam_role_policy.task.policy).Statement : s.Resource if s.Sid == "InvokeApprovedBedrockModels"]) == ["arn:aws:bedrock:ap-south-1::foundation-model/amazon.nova-lite-v1:0"]
    error_message = "Bedrock invoke must be limited to the one approved model ARN."
  }
}

run "secrets_are_injected_never_plain_environment_values" {
  command = apply

  assert {
    condition     = !anytrue([for e in jsondecode(aws_ecs_task_definition.app.container_definitions)[0].environment : can(regex("PASSWORD|SECRET|PRIVATE_KEY|API_KEY", e.name))])
    error_message = "No secret may appear as a plain environment value."
  }

  assert {
    condition     = length(jsondecode(aws_ecs_task_definition.app.container_definitions)[0].secrets) == 7
    error_message = "Expected 7 secrets injected from Secrets Manager (4 database, 2 anchor key, 1 OpenAI)."
  }

  assert {
    condition     = jsondecode(aws_ecs_task_definition.app.container_definitions)[0].readonlyRootFilesystem == true
    error_message = "The container filesystem must be read-only (only /tmp is writable)."
  }
}

run "service_is_two_tasks_with_automatic_rollback" {
  command = apply

  assert {
    condition     = aws_ecs_service.app.desired_count == 2 && aws_appautoscaling_target.app.min_capacity == 2
    error_message = "Two tasks minimum, so one can be replaced without downtime."
  }

  assert {
    condition     = one(aws_ecs_service.app.deployment_circuit_breaker).rollback == true
    error_message = "A failing deployment must roll back by itself."
  }

  assert {
    condition     = one(aws_ecs_service.app.network_configuration).assign_public_ip == false
    error_message = "Tasks must not get public addresses."
  }

  assert {
    condition     = aws_lb_target_group.app.health_check[0].path == "/actuator/health/readiness"
    error_message = "The load balancer must check the readiness endpoint."
  }
}

run "images_are_immutable_and_scanned" {
  command = apply

  assert {
    condition     = aws_ecr_repository.this.image_tag_mutability == "IMMUTABLE" && one(aws_ecr_repository.this.image_scanning_configuration).scan_on_push == true
    error_message = "ECR tags must be immutable and images scanned on push."
  }
}
