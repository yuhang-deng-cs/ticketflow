variable "aws_region" {
  description = "AWS deployment region (Sydney for AU market)"
  type        = string
  default     = "ap-southeast-2"
}

variable "environment" {
  description = "Deployment environment name"
  type        = string
  default     = "production"
}

variable "app_name" {
  description = "Application identifier"
  type        = string
  default     = "ticketflow"
}

variable "db_password" {
  description = "PostgreSQL master password. Pass via TF_VAR_db_password; never commit it."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.db_password) >= 16
    error_message = "db_password must be at least 16 characters."
  }
}

variable "jwt_secret" {
  description = "HS256 signing secret for JWTs (at least 32 bytes). Pass via TF_VAR_jwt_secret."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.jwt_secret) >= 32
    error_message = "jwt_secret must be at least 32 characters."
  }
}

variable "backend_image" {
  description = "Backend container image, e.g. ghcr.io/<owner>/ticketflow-backend:<git-sha>"
  type        = string
}

variable "inventory_strategy" {
  description = "Inventory strategy used by checkout"
  type        = string
  default     = "ATOMIC_UPDATE"

  validation {
    condition     = contains(["PESSIMISTIC_LOCK", "OPTIMISTIC_LOCK", "ATOMIC_UPDATE", "REDIS_LUA"], var.inventory_strategy)
    error_message = "inventory_strategy must be PESSIMISTIC_LOCK, OPTIMISTIC_LOCK, ATOMIC_UPDATE or REDIS_LUA."
  }
}

variable "lab_enabled" {
  description = "Expose the public Oversell Lab endpoints"
  type        = bool
  default     = true
}
