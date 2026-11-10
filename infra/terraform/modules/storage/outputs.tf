output "bucket_name" {
  value = aws_s3_bucket.anchors.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.anchors.arn
}

output "kms_key_arn" {
  value = aws_kms_key.anchors.arn
}
