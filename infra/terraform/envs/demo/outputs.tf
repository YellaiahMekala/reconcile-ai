output "app_url" {
  description = "Where the app answers (only from allowed_cidrs)."
  value       = "${var.certificate_arn == "" ? "http" : "https"}://${module.app.alb_dns_name}"
}

output "ecr_repository_url" {
  description = "Push the image here, tagged with the git sha."
  value       = module.app.ecr_repository_url
}

output "cluster_name" {
  value = module.app.cluster_name
}

output "service_name" {
  value = module.app.service_name
}

output "openai_secret_arn" {
  description = "Replace the placeholder value of this secret with your OpenAI key (put-secret-value)."
  value       = module.secrets.openai_secret_arn
}

output "anchor_bucket" {
  value = module.storage.bucket_name
}

output "ci_deploy_role_arn" {
  description = "Role for the deploy-aws workflow (empty when github_repository is not set)."
  value       = one(module.ci[*].deploy_role_arn)
}

output "ci_plan_role_arn" {
  value = one(module.ci[*].plan_role_arn)
}
