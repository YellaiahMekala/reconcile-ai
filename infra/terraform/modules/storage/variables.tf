variable "name" {
  description = "Prefix for resource names."
  type        = string
}

variable "bucket_name" {
  description = "Bucket name. Leave empty to use <name>-audit-anchors-<account id>, which is unique per account."
  type        = string
  default     = ""
}

variable "lock_mode" {
  description = "Object Lock default mode. COMPLIANCE cannot be shortened or removed by anyone; GOVERNANCE can be bypassed with a special permission, which lets a demo be destroyed."
  type        = string
  default     = "GOVERNANCE"

  validation {
    condition     = contains(["GOVERNANCE", "COMPLIANCE"], var.lock_mode)
    error_message = "lock_mode must be GOVERNANCE or COMPLIANCE."
  }
}

variable "retention_days" {
  description = "Default retention for anchor objects. The app also sets a per-object retention."
  type        = number
  default     = 1
}

variable "force_destroy" {
  description = "Let terraform destroy delete a non-empty bucket (demo only; needs GOVERNANCE mode)."
  type        = bool
  default     = false
}
