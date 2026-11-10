variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "Private subnets for the database (at least two zones)."
  type        = list(string)
}

variable "app_security_group_id" {
  description = "Security group of the app tasks; the only source allowed to reach Postgres."
  type        = string
}

variable "owner_password" {
  description = "Password of the owner login used by Flyway."
  type        = string
  sensitive   = true
}

variable "instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "allocated_storage_gb" {
  type    = number
  default = 20
}

variable "multi_az" {
  description = "Standby in a second zone. Doubles the cost, so off for the demo."
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = "Block accidental deletion. Off for the demo so destroy works; turn on for anything real."
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "Skip the snapshot taken at deletion. True for the demo (synthetic data only)."
  type        = bool
  default     = true
}
