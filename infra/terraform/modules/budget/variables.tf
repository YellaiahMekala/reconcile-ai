variable "name" {
  type = string
}

variable "monthly_limit_usd" {
  description = "Monthly cost limit in USD."
  type        = number
  default     = 25
}

variable "alert_emails" {
  description = "Where budget alerts go."
  type        = list(string)

  validation {
    condition     = length(var.alert_emails) > 0
    error_message = "At least one e-mail address is required, otherwise the budget alerts nobody."
  }
}
