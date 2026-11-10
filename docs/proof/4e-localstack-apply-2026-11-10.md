# 4E.4 proof, local edition: terraform apply → record → destroy against LocalStack (2026-11-10)

A real Terraform apply and destroy of the reconcile-ai modules, with no AWS account and no cost, against **LocalStack
4.9.2 Community** (AWS emulator in Docker, fixed test account `000000000000`, credentials `test`/`test`). The provider
in `infra/terraform/envs/localstack` routes every service endpoint to the emulator, so nothing could reach real AWS.

## What was applied

| Module | Resources | Emulated by LocalStack Community |
|---|---|---|
| network | VPC 10.20.0.0/16, 2 public + 2 private subnets, IGW, 1 NAT + EIP, route tables, S3 gateway endpoint, app SG (egress only), default SG emptied, VPC flow logs → CloudWatch Logs | yes |
| secrets | 3 Secrets Manager secrets (db, audit-anchor-key, openai placeholder), 2 random passwords, Ed25519 key pair | yes |
| storage | S3 bucket with Object Lock (GOVERNANCE, 1 day), versioning, KMS key (rotation on) + alias, SSE-KMS, public access block, TLS-only policy, lifecycle | yes |
| ci | GitHub OIDC provider, plan role, deploy role, scoped policies | yes |
| database, app, budget | RDS, ECS/ECR, ALB, Budgets | **no** (paid-tier emulation). Covered by the mock-provider tests only; the real `envs/demo` apply stays the proof for these |

`terraform init` → `apply`: **47 resources** created. `plan` straight after apply: **No changes** (no drift). `destroy`:
**47 destroyed, state empty**, and the emulator had 0 buckets, secrets, VPCs, NAT gateways, roles or KMS aliases left.

| Step | Wall time |
|---|---|
| apply (first attempt, failed on CloudWatch Logs, see below) | 84 s, 44 resources |
| apply (after fix) | 25 s, 3 more resources |
| plan after apply | no changes |
| destroy | 51 s |

## Checks made inside the emulator (awslocal, region ap-south-1)

```
Object Lock      {"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"GOVERNANCE","Days":1}}}
Public access    BlockPublicAcls, IgnorePublicAcls, BlockPublicPolicy, RestrictPublicBuckets: all true
Versioning       Enabled          Encryption   aws:kms with the module's key
KMS              KeyRotationEnabled: true, RotationPeriodInDays: 365
Secrets          reconcile-localstack/audit-anchor-key, /db, /openai-api-key (names only; values never printed)
IAM roles        reconcile-localstack-gha-plan, -gha-deploy, -vpc-flow; OIDC provider token.actions.githubusercontent.com
Deploy role      actions: ecr:GetAuthorizationToken, ecr push/pull on one repo, ecs:UpdateService/DescribeServices,
                 ecs:RegisterTaskDefinition/DescribeTaskDefinition, iam:PassRole (two roles). No wildcard.
Network          VPC 10.20.0.0/16; subnets 10.20.0.0/24, 10.20.1.0/24, 10.20.10.0/24, 10.20.11.0/24; 1 NAT gateway;
                 flow log ALL → cloud-watch-logs; default security group 0 ingress / 0 egress rules
```

**Object Lock really blocks a delete.** An object was written with `GOVERNANCE` retention of one day, then deleted by version:

```
An error occurred (AccessDenied) when calling the DeleteObject operation: Access Denied because object protected by object lock.
head-object → ObjectLockMode GOVERNANCE, ServerSideEncryption aws:kms
```

`terraform destroy` still emptied the bucket, because the module sets `force_destroy` and GOVERNANCE mode can be bypassed
by the account owner. The demo's design note holds: COMPLIANCE mode could not have been emptied.

## Defect found by the real apply (the mock tests could not see it)

The network module writes VPC flow logs to CloudWatch Logs. The first LocalStack provider block listed EC2, S3, KMS,
Secrets Manager, IAM and STS but not `logs`, so the apply failed with `UnrecognizedClientException` on the log group
after 44 resources. Fix: `logs` endpoint added, re-apply added the 3 flow-log resources. The real `envs/demo` provider
has no endpoint overrides, so it was never affected; the lesson recorded is that "which services does this stack
touch" is only answered by an apply.

## Static checks on the new environment

`terraform fmt`, `validate`, `tflint` (AWS ruleset) and checkov (**75 passed, 0 failed, 12 skipped** with the same
written reasons as the modules) all pass. A CI job applies and destroys this environment against a LocalStack service
container on every push, so the apply path is exercised continuously, not once.

## Not shown here

RDS, ECS, ECR, the load balancer and Budgets: paid-tier emulation. Those wait for the real `envs/demo` apply (about
0.20 USD per hour while it exists), which you will run when you have AWS access.
