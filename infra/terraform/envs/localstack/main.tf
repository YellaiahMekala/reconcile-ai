# reconcile-ai against LocalStack: a real apply, record and destroy cycle with no AWS account and no cost.
#
# LocalStack Community emulates EC2 networking, S3, KMS, Secrets Manager, IAM and STS, so this environment wires the
# modules that use only those: network, secrets, storage and ci. ECS, ECR, RDS, the load balancer and Budgets are
# paid-tier emulations, so the app, database and budget modules are not here; for them the mock-provider tests in
# each module and the real `envs/demo` apply remain the proof.
#
#   docker run -d --name reconcile-localstack -p 4566:4566 -e SERVICES=ec2,s3,kms,secretsmanager,iam,sts,logs localstack/localstack:4.9
#   cd infra/terraform/envs/localstack
#   terraform init && terraform apply -auto-approve && terraform destroy -auto-approve
#
# The account is LocalStack's fixed test account 000000000000 with the credentials "test"/"test". Nothing here can
# reach real AWS: every service endpoint points at the emulator.

provider "aws" {
  region     = var.region
  access_key = "test"
  secret_key = "test"

  s3_use_path_style           = true
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = false # STS is emulated, so the storage module's account-scoped names resolve

  endpoints {
    ec2            = var.endpoint
    s3             = var.endpoint
    kms            = var.endpoint
    secretsmanager = var.endpoint
    iam            = var.endpoint
    logs           = var.endpoint
    sts            = var.endpoint
  }

  default_tags {
    tags = {
      Project   = "reconcile-ai"
      Env       = "localstack"
      ManagedBy = "terraform"
    }
  }
}

locals {
  name = "${var.name_prefix}-localstack"
}

module "network" {
  source = "../../modules/network"
  name   = local.name
}

module "secrets" {
  source = "../../modules/secrets"
  name   = local.name
}

module "storage" {
  source        = "../../modules/storage"
  name          = local.name
  lock_mode     = "GOVERNANCE"
  force_destroy = true
}

# The deploy role is scoped to one ECR repository and one ECS service. Those services are not emulated here, so the
# ARNs are the shape the real apply would produce, in the test account.
module "ci" {
  source               = "../../modules/ci"
  name                 = local.name
  create_oidc_provider = true
  plan_subjects        = ["repo:${var.github_repository}:pull_request"]
  deploy_subjects      = ["repo:${var.github_repository}:environment:aws-demo"]
  state_bucket_arn     = "arn:aws:s3:::reconcile-tfstate-000000000000"
  ecr_repository_arn   = "arn:aws:ecr:${var.region}:000000000000:repository/${local.name}"
  ecs_service_arn      = "arn:aws:ecs:${var.region}:000000000000:service/${local.name}/${local.name}"
  passable_role_arns = [
    "arn:aws:iam::000000000000:role/${local.name}-execution",
    "arn:aws:iam::000000000000:role/${local.name}-task",
  ]
}
