variable "name" {
  type = string
}

variable "create_oidc_provider" {
  description = "Create the GitHub OIDC provider. Set false if the account already has one (only one per URL is allowed)."
  type        = bool
  default     = true
}

variable "existing_oidc_provider_arn" {
  description = "ARN of an existing GitHub OIDC provider, used when create_oidc_provider is false."
  type        = string
  default     = ""
}

variable "plan_subjects" {
  description = "GitHub OIDC subjects allowed to assume the read-only plan role, for example [\"repo:OWNER/REPO:pull_request\"]."
  type        = list(string)
}

variable "deploy_subjects" {
  description = "GitHub OIDC subjects allowed to assume the deploy role. Pin to a branch or environment, never a wildcard repo."
  type        = list(string)

  validation {
    condition     = alltrue([for s in var.deploy_subjects : !endswith(s, ":*")])
    error_message = "deploy_subjects must not end in a wildcard; name the branch or environment."
  }
}

variable "state_bucket_arn" {
  description = "ARN of the bucket holding Terraform state."
  type        = string
}

variable "ecr_repository_arn" {
  type = string
}

variable "ecs_service_arn" {
  type = string
}

variable "passable_role_arns" {
  description = "Task execution and task role ARNs the deploy role may hand to ECS."
  type        = list(string)
}
