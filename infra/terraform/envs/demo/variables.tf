variable "region" {
  description = "AWS region for the stack."
  type        = string
  default     = "ap-south-1"
}

variable "name_prefix" {
  description = "Prefix for every resource name."
  type        = string
  default     = "reconcile"
}

variable "allowed_cidrs" {
  description = "Addresses allowed to reach the load balancer, for example [\"203.0.113.7/32\"] (your own public IP). Required; 0.0.0.0/0 is refused."
  type        = list(string)

  validation {
    condition     = length(var.allowed_cidrs) > 0 && !contains(var.allowed_cidrs, "0.0.0.0/0")
    error_message = "allowed_cidrs must list at least one address and must not contain 0.0.0.0/0."
  }
}

variable "budget_emails" {
  description = "E-mail addresses for budget alerts. Required."
  type        = list(string)
}

variable "monthly_budget_usd" {
  description = "Monthly cost budget in USD; alerts fire at 50% and 80% actual and 100% forecast."
  type        = number
  default     = 25
}

variable "chat_profile" {
  description = "Spring profile for the chat model: openai, bedrock, or mock-llm (no model calls)."
  type        = string
  default     = "mock-llm"

  validation {
    condition     = contains(["openai", "bedrock", "mock-llm"], var.chat_profile)
    error_message = "chat_profile must be openai, bedrock or mock-llm."
  }
}

variable "bedrock_model_id" {
  description = "Bedrock foundation model id, used when chat_profile is bedrock. The model must be enabled in the Bedrock console."
  type        = string
  default     = "amazon.nova-lite-v1:0"
}

variable "bedrock_region" {
  description = "Region where the Bedrock model is enabled (model choice differs per region; us-east-1 has the widest)."
  type        = string
  default     = "us-east-1"
}

variable "image_tag" {
  description = "Image tag (git sha) the service runs. Push the image to ECR before the service can start."
  type        = string
  default     = "bootstrap"
}

variable "certificate_arn" {
  description = "ACM certificate ARN for HTTPS. Empty keeps the demo on HTTP port 80, still limited to allowed_cidrs."
  type        = string
  default     = ""
}

variable "oidc_issuer_uri" {
  description = "Issuer of your identity provider (for example a Cognito user pool). Empty uses the app's built-in demo key."
  type        = string
  default     = ""
}

variable "anchor_lock_mode" {
  description = "Object Lock mode for audit anchors. The demo uses GOVERNANCE so it can be destroyed; use COMPLIANCE for real."
  type        = string
  default     = "GOVERNANCE"
}

variable "anchor_retention_days" {
  description = "Retention for each anchor object."
  type        = number
  default     = 1
}

variable "github_repository" {
  description = "OWNER/REPO that may deploy through GitHub OIDC. Empty skips creating the CI roles."
  type        = string
  default     = ""
}

variable "create_oidc_provider" {
  description = "Create the GitHub OIDC provider; set false if your account already has one."
  type        = bool
  default     = true
}

variable "state_bucket" {
  description = "Name of the bucket holding Terraform state (created once by hand, see infra/README.md). Used to scope the plan role."
  type        = string
  default     = ""
}
