variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "app_security_group_id" {
  description = "Security group attached to the tasks (owned by the network module)."
  type        = string
}

variable "allowed_cidrs" {
  description = "Source addresses allowed to reach the load balancer, for example [\"203.0.113.7/32\"]. Open-to-the-world is refused."
  type        = list(string)

  validation {
    condition     = length(var.allowed_cidrs) > 0 && !contains(var.allowed_cidrs, "0.0.0.0/0")
    error_message = "allowed_cidrs must list at least one address and must not contain 0.0.0.0/0."
  }
}

variable "certificate_arn" {
  description = "ACM certificate for HTTPS. Empty means plain HTTP on port 80 (demo only, restricted to allowed_cidrs)."
  type        = string
  default     = ""
}

variable "image_tag" {
  description = "Image tag to run, normally the git commit sha."
  type        = string
  default     = "bootstrap"
}

variable "environment" {
  description = "Non-secret environment variables for the container."
  type        = map(string)
}

variable "db_secret_arn" {
  type = string
}

variable "anchor_secret_arn" {
  type = string
}

variable "openai_secret_arn" {
  type = string
}

variable "anchor_bucket_arn" {
  type = string
}

variable "anchor_kms_key_arn" {
  type = string
}

variable "bedrock_model_ids" {
  description = "Foundation model ids the task may invoke. Empty means no Bedrock access at all."
  type        = list(string)
  default     = []
}

variable "cpu" {
  type    = number
  default = 1024
}

variable "memory" {
  type    = number
  default = 2048
}

variable "desired_count" {
  type    = number
  default = 2
}

variable "min_tasks" {
  type    = number
  default = 2
}

variable "max_tasks" {
  type    = number
  default = 4
}

variable "log_retention_days" {
  type    = number
  default = 14
}

variable "force_delete_repository" {
  description = "Delete the image repository even if it holds images (demo only)."
  type        = bool
  default     = true
}
