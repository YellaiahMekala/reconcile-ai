variable "name" {
  description = "Prefix for secret names."
  type        = string
}

variable "recovery_window_days" {
  description = "Days a deleted secret can be restored. 0 deletes immediately, so a demo can be destroyed and re-created with the same names."
  type        = number
  default     = 0
}
