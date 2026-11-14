# Secrets live in SSM Parameter Store and are injected by ECS at task start,
# so they never appear in the task definition or the ECS console.

resource "aws_ssm_parameter" "db_password" {
  name  = "/${var.app_name}/${var.environment}/db_password"
  type  = "SecureString"
  value = var.db_password
}

resource "aws_ssm_parameter" "jwt_secret" {
  name  = "/${var.app_name}/${var.environment}/jwt_secret"
  type  = "SecureString"
  value = var.jwt_secret
}

resource "aws_iam_role_policy" "ecs_read_secrets" {
  name = "${var.app_name}-read-secrets"
  role = aws_iam_role.ecs_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = [aws_ssm_parameter.db_password.arn, aws_ssm_parameter.jwt_secret.arn]
      }
    ]
  })
}
