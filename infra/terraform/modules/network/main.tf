# VPC with public subnets (load balancer, NAT) and private subnets (app tasks, database) across two availability zones.
#
# One NAT gateway, not one per zone: the app must reach OpenAI for embeddings, and a NAT per zone would double a fixed
# monthly cost for a demo. The trade-off is that losing that one zone loses outbound traffic; production would use one
# NAT per zone. The S3 gateway endpoint is free and keeps audit-anchor traffic off the NAT.

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "${var.name}-vpc" }
}

# Closing the default security group means nothing can accidentally rely on it.
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.this.id
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-igw" }
}

resource "aws_subnet" "public" {
  count                   = var.az_count
  vpc_id                  = aws_vpc.this.id
  availability_zone       = local.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-public-${count.index}", Tier = "public" }
}

resource "aws_subnet" "private" {
  count                   = var.az_count
  vpc_id                  = aws_vpc.this.id
  availability_zone       = local.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-private-${count.index}", Tier = "private" }
}

resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = { Name = "${var.name}-nat" }
}

resource "aws_nat_gateway" "this" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id
  tags          = { Name = "${var.name}-nat" }
  depends_on    = [aws_internet_gateway.this]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.this.id
  }
  tags = { Name = "${var.name}-private" }
}

resource "aws_route_table_association" "public" {
  count          = var.az_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  count          = var.az_count
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

data "aws_region" "current" {}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]
  tags              = { Name = "${var.name}-s3" }
}

# Flow logs: rejected and accepted traffic metadata for incident review. Kept 14 days to limit cost.
resource "aws_cloudwatch_log_group" "flow" {
  #checkov:skip=CKV_AWS_338: Demo keeps 14 days of logs to limit cost; the group is encrypted with the AWS-owned key. Production: 365 days and a customer-managed key.
  #checkov:skip=CKV_AWS_158: Demo keeps 14 days of logs to limit cost; the group is encrypted with the AWS-owned key. Production: 365 days and a customer-managed key.
  name              = "/${var.name}/vpc-flow"
  retention_in_days = 14
}

resource "aws_iam_role" "flow" {
  name = "${var.name}-vpc-flow"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "flow" {
  name = "write-flow-logs"
  role = aws_iam_role.flow.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
      Resource = "${aws_cloudwatch_log_group.flow.arn}:*"
    }]
  })
}

resource "aws_flow_log" "this" {
  vpc_id          = aws_vpc.this.id
  traffic_type    = "ALL"
  iam_role_arn    = aws_iam_role.flow.arn
  log_destination = aws_cloudwatch_log_group.flow.arn
}

# Attached to the app tasks. It has no inbound rules here: the app module opens port 8080 from the load balancer only.
resource "aws_security_group" "app" {
  #checkov:skip=CKV2_AWS_5: False positive: this group is attached to the ECS service in the app module (passed in as a variable).
  name_prefix = "${var.name}-app-"
  description = "App tasks. Inbound is added by the app module (load balancer only); outbound is HTTPS and Postgres."
  vpc_id      = aws_vpc.this.id
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_egress_rule" "app_https" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS to OpenAI, Bedrock, Secrets Manager, ECR and CloudWatch (via NAT)"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "app_postgres" {
  security_group_id = aws_security_group.app.id
  description       = "Postgres inside the VPC only"
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 5432
  cidr_ipv4         = var.vpc_cidr
}
