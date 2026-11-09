# reconcile-ai on AWS (Terraform): deploy, record, destroy

This builds the app on AWS for a short demo, so you can show it running and then tear it down. **It costs money while it
exists, about 0.20 USD per hour.** Nothing here runs on its own: CI only checks the code, and the deploy workflow is manual.

```
Internet ──(only your IP)──► ALB ──► ECS Fargate x2 (private subnets) ──► RDS Postgres 16 + pgvector (private)
                                          │                         └──► S3 Object Lock bucket (audit anchors, KMS)
                                          ├──► Secrets Manager (DB passwords, anchor signing key, OpenAI key)
                                          └──► NAT ──► OpenAI (embeddings, optional chat)   /   Bedrock (optional chat)
```

## What is tested without an AWS account (free, runs in CI)

| Check | Command | What it proves |
|---|---|---|
| Format | `terraform fmt -check -recursive` | Consistent style |
| Validate | `terraform init -backend=false && terraform validate` | The configuration is valid |
| Unit tests (38 runs, mock AWS provider) | `terraform test` in each module and in `envs/demo` | The security rules below hold, with no AWS call |
| tflint (AWS ruleset) | `tflint` | Invalid instance types, deprecated syntax, and so on |
| checkov | `checkov -d . --framework terraform` | 166 policy checks pass; 24 are skipped on purpose, each with a written reason next to the resource |

The tests pin the things that matter, for example: the load balancer refuses `0.0.0.0/0`, the database has no public address
and is reachable only from the app, the anchor bucket has Object Lock and blocks public access, the task role has no wildcard
actions and no Bedrock permission unless a model is named, no secret appears as a plain environment value, and the deploy
role cannot create or change infrastructure.

**What the tests do not prove:** that AWS accepts the configuration (a mock provider cannot know), and that the app starts
on Fargate. That is what the real apply is for.

## Cost (list prices, check the AWS pricing calculator for your region)

| Piece | Approx. per hour |
|---|---|
| NAT gateway (the app calls OpenAI for embeddings) | 0.045 + data |
| Application Load Balancer | 0.023 |
| RDS db.t4g.micro, single zone | 0.018 |
| Fargate, 2 tasks of 1 vCPU / 2 GB | 0.11 |
| **Total** | **about 0.20, so roughly 5 per day and 140 per month if left running** |

A few hours of deploy-record-destroy is about 1 to 3 USD plus model calls. A monthly budget with e-mail alerts at 50% and
80% actual and 100% forecast is created for you (default 25 USD). The budget only warns; **destroying the stack is the real
control.**

## Before you start (once)

1. An AWS account you control, with a billing alert you trust, and credentials on your machine (`aws sso login` or an access key).
   Use an admin-ish role only for this one-off setup and the `apply`.
2. Terraform 1.13+ (or the Docker image `hashicorp/terraform:1.13`), Docker, AWS CLI.
3. Your public IP, from https://checkip.amazonaws.com. The load balancer accepts only this address.
4. The state bucket (Terraform cannot create the bucket that stores its own state). Pick a unique name:

   ```powershell
   $b = "reconcile-tfstate-<your-account-id>"; $r = "ap-south-1"
   aws s3api create-bucket --bucket $b --region $r --create-bucket-configuration LocationConstraint=$r
   aws s3api put-bucket-versioning --bucket $b --versioning-configuration Status=Enabled
   aws s3api put-bucket-encryption --bucket $b --server-side-encryption-configuration '{\"Rules\":[{\"ApplyServerSideEncryptionByDefault\":{\"SSEAlgorithm\":\"aws:kms\"}}]}'
   aws s3api put-public-access-block --bucket $b --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
   ```

   State contains the generated database passwords and the anchor signing key, so keep this bucket private. It costs cents.
5. If you want Bedrock: in the Bedrock console (region `us-east-1` by default) enable access to the model
   (`amazon.nova-lite-v1:0` by default).

## Deploy

```powershell
cd infra/terraform/envs/demo
Copy-Item terraform.tfvars.example terraform.tfvars      # edit: allowed_cidrs, budget_emails (and chat_profile if wanted)
Copy-Item backend.hcl.example backend.hcl                # edit: your state bucket and region
terraform init -backend-config=backend.hcl
terraform plan                                           # read it: nothing should be public except the load balancer
```

Apply in two steps, because the service cannot start until an image exists in ECR (tags are immutable, so use the commit sha):

```powershell
terraform apply -target=module.app.aws_ecr_repository.this      # 1. just the image repository
$sha = git rev-parse --short HEAD
$repo = terraform output -raw ecr_repository_url
aws ecr get-login-password | docker login --username AWS --password-stdin ($repo.Split('/')[0])
docker build -t "${repo}:$sha" ../../../..               # from the reconcile-ai folder; the Dockerfile builds the jar
docker push "${repo}:$sha"
terraform apply -var "image_tag=$sha"                    # 2. everything else; takes about 10 minutes (RDS is the slow part)
```

Then give the app your OpenAI key. Terraform never sees it, so it does not end up in state. Embeddings always need it:

```powershell
aws secretsmanager put-secret-value --secret-id (terraform output -raw openai_secret_arn) --secret-string "<your key>"
aws ecs update-service --cluster (terraform output -raw cluster_name) --service (terraform output -raw service_name) --force-new-deployment
```

Open `terraform output app_url` from the allowed address. Run `scripts/demo-golden-path.ps1` against it.

## Record

Save evidence into `docs/proof/aws-deploy-<date>.md`:

- screenshots: ECS service with 2 running tasks, target group healthy, RDS, the S3 anchor bucket with a locked object, CloudWatch logs;
- the golden-path script output, `/actuator/health`, one anchor verify call;
- measured numbers: time from `apply` to healthy, p50 and p95 latency of an investigation, the cost shown by Cost Explorer for the session.

## Destroy (do not skip)

```powershell
terraform destroy
```

Then check the console for leftovers: the NAT gateway, load balancer and RDS instance are the expensive ones. The demo
uses Object Lock in GOVERNANCE mode with 1-day retention so destroy can empty the bucket; a COMPLIANCE-mode bucket cannot be
emptied until retention ends, by design. The state bucket stays (it costs cents); delete it by hand if you are done for good.

## Deploy from GitHub Actions (optional)

Set `github_repository = "OWNER/REPO"` and `state_bucket = "..."` in `terraform.tfvars` and apply. That creates the GitHub OIDC
provider (set `create_oidc_provider = false` if your account already has one) and two roles:

| Role | Can do | Cannot do |
|---|---|---|
| plan | Read everything, read and write the state file | Change any resource |
| deploy | Push an image to the one ECR repository, update the one ECS service, pass the two task roles to ECS | Create or change infrastructure, read secrets or data |

Add the repository variables listed at the top of `.github/workflows/deploy-aws.yml`, and create an environment named
`aws-demo` with a required reviewer. Then run the **deploy-aws** workflow by hand: `plan` is read-only, `deploy` builds the
image, pushes it and rolls it out. It cannot smoke-test the app, because the load balancer only accepts your own address;
run the golden-path script from your machine.

## Design choices and honest limits

- **Region `ap-south-1`** (Mumbai) for the stack, **`us-east-1`** for Bedrock (widest model choice). Both are variables.
- **One NAT gateway**, not one per zone: the cheaper, less resilient choice. Losing that zone loses outbound traffic.
- **No Redis, Kafka or Keycloak.** Live progress streams are held per task, so with two tasks a client connected to one task can miss
  events from a job running on the other (the job result itself is always in Postgres and can be fetched). ElastiCache (shared stream fan-out and cache) and MSK are the scale-out steps.
- **JWT validation:** set `oidc_issuer_uri` (for example a Cognito user pool) to validate real tokens. Without it the app uses
  its built-in demo key, which is acceptable only because the load balancer is closed to everyone but you.
- **HTTP unless you give a certificate:** with `certificate_arn` the load balancer serves HTTPS only; without one it serves HTTP
  on port 80 to your address only. Do not put real data through the HTTP demo.
- **Secrets rotation, WAF, access logs, multi-AZ database, deletion protection, cross-region replication** are production steps,
  skipped on purpose for cost and recorded as explicit checkov skips next to each resource.
- **Agent mode on Bedrock** is not wired (the agent uses an OpenAI model); single-call investigation works with both providers.
- **`terraform destroy` removes the secrets immediately** (recovery window 0) so the next demo can reuse the names.
- **Not yet run against a real AWS account.** Everything above is validated by static checks and mock-provider tests only.
