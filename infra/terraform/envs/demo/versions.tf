terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.90"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # State lives in a private, encrypted, versioned S3 bucket you create once (see infra/README.md). Bucket, key and
  # region are supplied at init time so no account details sit in git:
  #   terraform init -backend-config=backend.hcl
  backend "s3" {
    use_lockfile = true
    encrypt      = true
  }
}
