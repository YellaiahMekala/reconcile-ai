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
  name                  = "t"
  vpc_id                = "vpc-0123456789abcdef0"
  subnet_ids            = ["subnet-aaaa1111", "subnet-bbbb2222"]
  app_security_group_id = "sg-0123456789abcdef0"
  owner_password        = "not-a-real-password"
}

run "database_is_private_encrypted_and_postgres_16" {
  command = apply

  assert {
    condition     = aws_db_instance.this.publicly_accessible == false
    error_message = "The database must not have a public address."
  }

  assert {
    condition     = aws_db_instance.this.storage_encrypted == true
    error_message = "Storage must be encrypted."
  }

  assert {
    condition     = aws_db_instance.this.engine == "postgres" && aws_db_instance.this.engine_version == "16"
    error_message = "pgvector needs Postgres 16 here."
  }

  assert {
    condition     = aws_db_instance.this.instance_class == "db.t4g.micro" && aws_db_instance.this.multi_az == false
    error_message = "The demo default is the cheapest single-zone instance."
  }
}

run "only_the_app_can_reach_postgres" {
  command = apply

  assert {
    condition     = aws_vpc_security_group_ingress_rule.from_app.referenced_security_group_id == "sg-0123456789abcdef0" && aws_vpc_security_group_ingress_rule.from_app.from_port == 5432
    error_message = "Postgres ingress must come from the app security group on 5432 only."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.from_app.cidr_ipv4 == null
    error_message = "No CIDR-based ingress is allowed."
  }
}

run "tls_is_forced" {
  command = apply

  assert {
    condition     = one([for p in aws_db_parameter_group.this.parameter : p.value if p.name == "rds.force_ssl"]) == "1"
    error_message = "rds.force_ssl must be 1."
  }
}

run "real_deployments_can_keep_a_final_snapshot" {
  command = apply
  variables {
    skip_final_snapshot = false
    deletion_protection = true
  }

  assert {
    condition     = aws_db_instance.this.deletion_protection == true && aws_db_instance.this.final_snapshot_identifier == "t-db-final"
    error_message = "A real deployment must be able to turn on deletion protection and a final snapshot."
  }
}
