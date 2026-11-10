# RDS PostgreSQL 16 for the app. pgvector ships with RDS Postgres 16; Flyway's first migration runs CREATE EXTENSION
# vector as the owner user (a member of rds_superuser), and later migrations create the restricted application login.
# The database is private: no public address, reachable only from the app security group.

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-db"
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "db" {
  name_prefix = "${var.name}-db-"
  description = "Postgres, reachable from the app tasks only"
  vpc_id      = var.vpc_id
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "from_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "Postgres from the app tasks"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = var.app_security_group_id
}

# Refuse unencrypted connections; the app uses sslmode=require.
resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.name}-pg16-"
  family      = "postgres16"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  #checkov:skip=CKV_AWS_118: Demo: enhanced monitoring adds cost; CloudWatch basic metrics and the postgresql log export are on.
  #checkov:skip=CKV_AWS_353: Demo: Performance Insights not needed for a short-lived stack.
  #checkov:skip=CKV_AWS_293: Demo: destroy must work. Variable deletion_protection turns it on for real use.
  #checkov:skip=CKV_AWS_157: Demo: single zone halves the cost. Variable multi_az turns it on for real use.
  identifier     = "${var.name}-db"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.instance_class

  db_name  = "reconcile"
  username = "reconcile"
  password = var.owner_password

  allocated_storage = var.allocated_storage_gb
  storage_type      = "gp3"
  storage_encrypted = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  multi_az               = var.multi_az

  backup_retention_period             = 1
  auto_minor_version_upgrade          = true
  copy_tags_to_snapshot               = true
  iam_database_authentication_enabled = true
  enabled_cloudwatch_logs_exports     = ["postgresql", "upgrade"]

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${var.name}-db-final"
}
