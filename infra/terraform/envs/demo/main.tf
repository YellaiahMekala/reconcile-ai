# reconcile-ai demo environment: deploy, record, destroy. Wires the modules together; every cost-relevant choice is a
# variable with a cheap default. Read infra/README.md before running apply: it costs money while it exists.

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "reconcile-ai"
      Env       = "demo"
      ManagedBy = "terraform"
    }
  }
}

locals {
  name = "${var.name_prefix}-demo"

  # JWT validation: with OIDC_ISSUER_URI the app validates tokens from your identity provider (Cognito, Entra ID, ...).
  # Without it the app falls back to its built-in demo key, which is fine ONLY because the load balancer is closed to
  # everyone except allowed_cidrs.
  oidc_env = var.oidc_issuer_uri == "" ? {} : { OIDC_ISSUER_URI = var.oidc_issuer_uri }

  # Chat model: bedrock needs no extra secret; openai uses the key you put in Secrets Manager. Embeddings are always
  # OpenAI (text-embedding-3-small), so the OpenAI secret is needed either way.
  bedrock_env = var.chat_profile == "bedrock" ? {
    BEDROCK_CHAT_MODEL = var.bedrock_model_id
    AWS_REGION         = var.bedrock_region
  } : {}
}

module "network" {
  source = "../../modules/network"
  name   = local.name
}

module "secrets" {
  source = "../../modules/secrets"
  name   = local.name
}

module "database" {
  source                = "../../modules/database"
  name                  = local.name
  vpc_id                = module.network.vpc_id
  subnet_ids            = module.network.private_subnet_ids
  app_security_group_id = module.network.app_security_group_id
  owner_password        = module.secrets.db_owner_password
}

module "storage" {
  source        = "../../modules/storage"
  name          = local.name
  lock_mode     = var.anchor_lock_mode
  force_destroy = true
}

module "app" {
  source                = "../../modules/app"
  name                  = local.name
  vpc_id                = module.network.vpc_id
  public_subnet_ids     = module.network.public_subnet_ids
  private_subnet_ids    = module.network.private_subnet_ids
  app_security_group_id = module.network.app_security_group_id
  allowed_cidrs         = var.allowed_cidrs
  certificate_arn       = var.certificate_arn
  image_tag             = var.image_tag
  db_secret_arn         = module.secrets.db_secret_arn
  anchor_secret_arn     = module.secrets.anchor_secret_arn
  openai_secret_arn     = module.secrets.openai_secret_arn
  anchor_bucket_arn     = module.storage.bucket_arn
  anchor_kms_key_arn    = module.storage.kms_key_arn
  bedrock_model_ids     = var.chat_profile == "bedrock" ? [var.bedrock_model_id] : []

  environment = merge({
    SPRING_PROFILES_ACTIVE                    = var.chat_profile
    DB_URL                                    = "jdbc:postgresql://${module.database.address}:${module.database.port}/reconcile?sslmode=require"
    AUDIT_ANCHOR_ENABLED                      = "true"
    AUDIT_ANCHOR_S3_BUCKET                    = module.storage.bucket_name
    AUDIT_ANCHOR_S3_REGION                    = var.region
    AUDIT_ANCHOR_LOCK_MODE                    = var.anchor_lock_mode
    AUDIT_ANCHOR_RETENTION_DAYS               = tostring(var.anchor_retention_days)
    DIGEST_ENABLED                            = "true"
    CACHE_ENABLED                             = "false" # no Redis in the demo; ElastiCache is the documented scale-out step
    KAFKA_ENABLED                             = "false" # no MSK in the demo
    TRACING_ENABLED                           = "false"
    SERVER_SHUTDOWN                           = "graceful"
    MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED = "true"
  }, local.oidc_env, local.bedrock_env)
}

module "budget" {
  source            = "../../modules/budget"
  name              = local.name
  monthly_limit_usd = var.monthly_budget_usd
  alert_emails      = var.budget_emails
}

# Only created when you give a repository: lets GitHub Actions deploy images without stored AWS keys.
module "ci" {
  count                = var.github_repository == "" ? 0 : 1
  source               = "../../modules/ci"
  name                 = local.name
  create_oidc_provider = var.create_oidc_provider
  plan_subjects        = ["repo:${var.github_repository}:pull_request"]
  deploy_subjects      = ["repo:${var.github_repository}:environment:aws-demo"]
  state_bucket_arn     = "arn:aws:s3:::${var.state_bucket}"
  ecr_repository_arn   = module.app.ecr_repository_arn
  ecs_service_arn      = module.app.service_arn
  passable_role_arns   = [module.app.execution_role_arn, module.app.task_role_arn]
}
