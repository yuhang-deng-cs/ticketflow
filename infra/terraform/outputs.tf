output "alb_dns_name" {
  description = "Application Load Balancer DNS Endpoint"
  value       = aws_lb.main.dns_name
}

output "rds_endpoint" {
  description = "PostgreSQL RDS connection endpoint"
  value       = aws_db_instance.postgres.endpoint
}

output "cloudfront_domain_name" {
  description = "CloudFront CDN URL for Frontend SPA"
  value       = aws_cloudfront_distribution.cdn.domain_name
}

output "app_url" {
  description = "Public URL of the app (SPA + /api)"
  value       = "https://${aws_cloudfront_distribution.cdn.domain_name}"
}

output "frontend_bucket" {
  description = "Upload the built SPA here: aws s3 sync frontend/dist s3://<bucket>"
  value       = aws_s3_bucket.frontend.bucket
}

