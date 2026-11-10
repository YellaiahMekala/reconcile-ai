output "vpc_id" {
  value = module.network.vpc_id
}

output "private_subnet_ids" {
  value = module.network.private_subnet_ids
}

output "anchor_bucket" {
  value = module.storage.bucket_name
}

output "anchor_kms_key_arn" {
  value = module.storage.kms_key_arn
}

output "openai_secret_arn" {
  value = module.secrets.openai_secret_arn
}

output "ci_plan_role_arn" {
  value = module.ci.plan_role_arn
}

output "ci_deploy_role_arn" {
  value = module.ci.deploy_role_arn
}
