# GitHub Actions authenticates to AWS with OIDC: a short-lived token per workflow run, no stored access keys.
#
# Two roles with different power:
#   plan    read-only. Lets a pull request run terraform plan and see what would change.
#   deploy  can only roll out a new image (push to ECR, update the ECS service). It cannot create or change
#           infrastructure; that stays a deliberate, human-run terraform apply.

locals {
  oidc_host         = "token.actions.githubusercontent.com"
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : var.existing_oidc_provider_arn

  trust = { for role, subjects in {
    plan   = var.plan_subjects
    deploy = var.deploy_subjects
    } : role => jsonencode({
      Version = "2012-10-17"
      Statement = [{
        Effect    = "Allow"
        Principal = { Federated = local.oidc_provider_arn }
        Action    = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = { "${local.oidc_host}:aud" = "sts.amazonaws.com" }
          StringLike   = { "${local.oidc_host}:sub" = subjects }
        }
      }]
  }) }
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_oidc_provider ? 1 : 0
  url            = "https://${local.oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "plan" {
  name               = "${var.name}-gha-plan"
  assume_role_policy = local.trust["plan"]
}

resource "aws_iam_role_policy_attachment" "plan_readonly" {
  role       = aws_iam_role.plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

resource "aws_iam_role_policy" "plan_state" {
  name = "terraform-state"
  role = aws_iam_role.plan.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "${var.state_bucket_arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = var.state_bucket_arn
      }
    ]
  })
}

resource "aws_iam_role" "deploy" {
  name               = "${var.name}-gha-deploy"
  assume_role_policy = local.trust["deploy"]
}

resource "aws_iam_role_policy" "deploy" {
  name = "roll-out-image"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*" # this action does not support resource scoping
      },
      {
        Sid    = "PushImage"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:DescribeImages"
        ]
        Resource = var.ecr_repository_arn
      },
      {
        Sid      = "UpdateService"
        Effect   = "Allow"
        Action   = ["ecs:UpdateService", "ecs:DescribeServices"]
        Resource = var.ecs_service_arn
      },
      {
        Sid      = "TaskDefinitions"
        Effect   = "Allow"
        Action   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition"]
        Resource = "*" # task definition actions do not support resource scoping
      },
      {
        Sid       = "HandRolesToEcs"
        Effect    = "Allow"
        Action    = "iam:PassRole"
        Resource  = var.passable_role_arns
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      }
    ]
  })
}
