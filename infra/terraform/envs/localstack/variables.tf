variable "region" {
  description = "Region name LocalStack should pretend to be."
  type        = string
  default     = "ap-south-1"
}

variable "name_prefix" {
  type    = string
  default = "reconcile"
}

variable "endpoint" {
  description = "LocalStack edge endpoint. Use http://host.docker.internal:4566 when Terraform itself runs in Docker."
  type        = string
  default     = "http://localhost:4566"
}

variable "github_repository" {
  description = "OWNER/REPO used only to shape the OIDC trust policy subjects."
  type        = string
  default     = "example-org/reconcile-ai"
}
