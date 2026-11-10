output "db_secret_arn" {
  value = aws_secretsmanager_secret.db.arn
}

output "anchor_secret_arn" {
  value = aws_secretsmanager_secret.anchor.arn
}

output "openai_secret_arn" {
  value = aws_secretsmanager_secret.openai.arn
}

output "db_owner_password" {
  value     = random_password.db_owner.result
  sensitive = true
}
