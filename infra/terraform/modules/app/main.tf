# The app on ECS Fargate behind an Application Load Balancer.
#
# Two tasks in private subnets, spread over two zones. The load balancer accepts traffic only from var.allowed_cidrs
# (your address, not the internet). Secrets are injected by ECS from Secrets Manager. The task role can write anchors to
# one bucket and invoke named Bedrock models, and nothing else. There are no AWS access keys anywhere.

data "aws_region" "current" {}

locals {
  image          = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"
  has_cert       = var.certificate_arn != ""
  listener_port  = local.has_cert ? 443 : 80
  bedrock_models = [for m in var.bedrock_model_ids : "arn:aws:bedrock:${data.aws_region.current.name}::foundation-model/${m}"]
  ecs_assume = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# ---------------------------------------------------------------- image registry

resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = "IMMUTABLE" # a tag always means the same bytes; deploy by commit sha
  force_delete         = var.force_delete_repository

  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the 10 most recent images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
      action       = { type = "expire" }
    }]
  })
}

# ---------------------------------------------------------------- logs

resource "aws_cloudwatch_log_group" "app" {
  #checkov:skip=CKV_AWS_338: Demo keeps 14 days of logs to limit cost; the group is encrypted with the AWS-owned key. Production: 365 days and a customer-managed key.
  #checkov:skip=CKV_AWS_158: Demo keeps 14 days of logs to limit cost; the group is encrypted with the AWS-owned key. Production: 365 days and a customer-managed key.
  name              = "/ecs/${var.name}"
  retention_in_days = var.log_retention_days
}

# ---------------------------------------------------------------- IAM

# Execution role: used by ECS itself to pull the image, write logs and read the three secrets. Not used by app code.
resource "aws_iam_role" "execution" {
  name               = "${var.name}-task-execution"
  assume_role_policy = local.ecs_assume
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-app-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [var.db_secret_arn, var.anchor_secret_arn, var.openai_secret_arn]
    }]
  })
}

# Task role: what the running app is allowed to do.
resource "aws_iam_role" "task" {
  name               = "${var.name}-task"
  assume_role_policy = local.ecs_assume
}

resource "aws_iam_role_policy" "task" {
  name = "app-permissions"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid      = "WriteAndReadAuditAnchors"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:PutObjectRetention", "s3:GetObject", "s3:GetObjectVersion"]
        Resource = "${var.anchor_bucket_arn}/*"
      },
      {
        Sid      = "AnchorKeyUse"
        Effect   = "Allow"
        Action   = ["kms:GenerateDataKey", "kms:Decrypt"]
        Resource = var.anchor_kms_key_arn
      }
      ], length(local.bedrock_models) == 0 ? [] : [
      {
        Sid      = "InvokeApprovedBedrockModels"
        Effect   = "Allow"
        Action   = ["bedrock:InvokeModel"]
        Resource = local.bedrock_models
      }
    ])
  })
}

# ---------------------------------------------------------------- network access

resource "aws_security_group" "alb" {
  name_prefix = "${var.name}-alb-"
  description = "Load balancer: inbound only from the allowed CIDRs"
  vpc_id      = var.vpc_id
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_in" {
  for_each          = toset(var.allowed_cidrs)
  security_group_id = aws_security_group.alb.id
  description       = "Client access from ${each.value}"
  ip_protocol       = "tcp"
  from_port         = local.listener_port
  to_port           = local.listener_port
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to the app tasks"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = var.app_security_group_id
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = var.app_security_group_id
  description                  = "App port from the load balancer only"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.alb.id
}

# ---------------------------------------------------------------- load balancer

resource "aws_lb" "this" {
  #checkov:skip=CKV_AWS_150: Demo only: the stack must be destroyable. Production sets enable_deletion_protection = true.
  #checkov:skip=CKV_AWS_91: Access logs need a bucket and add cost; the demo is reachable only from allowed_cidrs. Production enables them.
  #checkov:skip=CKV2_AWS_20: With certificate_arn the only listener is HTTPS. The plain HTTP listener exists only in the demo and is limited to allowed_cidrs.
  #checkov:skip=CKV2_AWS_28: A WAF adds a fixed monthly fee; the demo is protected by an IP allow-list instead. Production adds a WAF.
  name                       = var.name
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = var.public_subnet_ids
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
  enable_deletion_protection = false # demo: destroy must work
}

resource "aws_lb_target_group" "app" {
  #checkov:skip=CKV_AWS_378: TLS ends at the load balancer; the hop to the task stays inside the private subnets and the security groups allow only the load balancer.
  name                 = var.name
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# With a certificate: HTTPS on 443. Without one (demo): plain HTTP on 80, restricted to allowed_cidrs.
resource "aws_lb_listener" "https" {
  count             = local.has_cert ? 1 : 0
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

resource "aws_lb_listener" "http" {
  #checkov:skip=CKV_AWS_103: Plain HTTP exists only in the demo (no certificate) and only for allowed_cidrs. With certificate_arn this listener is not created and HTTPS uses a TLS 1.3/1.2 policy.
  #checkov:skip=CKV_AWS_2: Demo has no certificate or domain, so HTTPS cannot be issued. The listener is open only to allowed_cidrs. With certificate_arn it is not created. Production: HTTPS only, plus an HTTP to HTTPS redirect.
  count             = local.has_cert ? 0 : 1
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# ---------------------------------------------------------------- service

resource "aws_ecs_cluster" "this" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_ecs_task_definition" "app" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([{
    name                   = "app"
    image                  = local.image
    essential              = true
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = 8080, protocol = "tcp" }]
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    environment            = [for k, v in var.environment : { name = k, value = v }]
    secrets = [
      { name = "DB_USER", valueFrom = "${var.db_secret_arn}:owner_user::" },
      { name = "DB_PASSWORD", valueFrom = "${var.db_secret_arn}:owner_pass::" },
      { name = "DB_APP_USER", valueFrom = "${var.db_secret_arn}:app_user::" },
      { name = "DB_APP_PASSWORD", valueFrom = "${var.db_secret_arn}:app_password::" },
      { name = "AUDIT_ANCHOR_PRIVATE_KEY", valueFrom = "${var.anchor_secret_arn}:private_key::" },
      { name = "AUDIT_ANCHOR_PUBLIC_KEY", valueFrom = "${var.anchor_secret_arn}:public_key::" },
      { name = "OPENAI_API_KEY", valueFrom = var.openai_secret_arn },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = data.aws_region.current.name
        "awslogs-stream-prefix" = "app"
      }
    }
  }])
}

resource "aws_ecs_service" "app" {
  name            = var.name
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # Flyway and context start take about a minute; do not let the load balancer kill a task that is still booting.
  health_check_grace_period_seconds = 180

  deployment_circuit_breaker {
    enable   = true
    rollback = true # a bad image rolls back by itself
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.app_security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = 8080
  }

  lifecycle {
    ignore_changes = [desired_count] # autoscaling owns the count after creation
  }

  depends_on = [aws_lb_listener.http, aws_lb_listener.https]
}

resource "aws_appautoscaling_target" "app" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.app.name}"
  min_capacity       = var.min_tasks
  max_capacity       = var.max_tasks
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.app.service_namespace
  scalable_dimension = aws_appautoscaling_target.app.scalable_dimension
  resource_id        = aws_appautoscaling_target.app.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 120
    scale_out_cooldown = 60
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
