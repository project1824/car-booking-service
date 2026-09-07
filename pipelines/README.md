# CI/CD Pipeline Setup

Single-environment pipeline (production only, on purpose - this is a take-home
assignment, not a multi-team org, so a dev/uat/prod promotion chain would be more
pipeline than the project needs). Every value that has to be real infrastructure (a
URL, a cluster name, a credential) is a clearly marked `REPLACE_ME` placeholder.
Nothing here has ever been run against real Azure/SonarQube infrastructure - this is
unavoidable without access to that infrastructure, so treat the YAML as carefully
written and internally consistent, not as pipeline-tested.

## What's here

| File | Purpose |
|---|---|
| `azure-pipelines.yml` | The pipeline - import this as the primary pipeline. Runs on every push/PR to `main`. |
| `pipelines/templates/*.yml` | The stages, broken into reusable files. See the note at the top of `azure-pipelines.yml` about how these would normally live in a shared, org-wide template repo rather than copied into each project - kept local here since there's no separate template repo to point at for a single-repo assignment. |
| `helm/car-booking-service/` | The Helm chart the pipeline deploys. |

## One-time setup checklist

### 1. Service connections
Project Settings > Service connections > New service connection:

| Name (must match the pipeline variable) | Type | Used for |
|---|---|---|
| `containerRegistryServiceConnection` value | Docker Registry (Azure Container Registry) | Building/pushing the image |
| `sonarQubeServiceConnection` value | SonarQube (or SonarCloud) | Code quality analysis |
| `azureServiceConnection` value | Azure Resource Manager | `az aks get-credentials` + `kubectl`/`helm` against the production AKS cluster |

Any container registry works here - ACR is used because it's the natural default alongside AKS. Swap the service connection type and `containerRegistryLoginServer` if using JFrog Artifactory, ECR, GCR, or anything else; `pipelines/templates/docker-build-push.yml` doesn't otherwise care which one it is.

### 2. Variable group (Pipelines > Library)
Create one group named `car-booking-service-production` with at minimum:

| Variable | Secret? | Notes |
|---|---|---|
| `dbPassword` | Yes | The real production Postgres password. Link the group to Azure Key Vault instead of typing it in directly if you have one - see the main README's "Secrets in a real deployment" section. |
| `nvdApiKey` | Yes | A free key from https://nvd.nist.gov/developers/request-an-api-key, used by the OWASP Dependency-Check step. Without it, that step is rate-limited and can take 20+ minutes. |

### 3. The Azure DevOps Environment + approval
The first pipeline run auto-creates an Environment named `production` (from the `environment:` key in `pipelines/templates/deploy-and-rollback.yml`). Immediately after that first run:

Project Settings > Environments > **production** > Approvals and checks > **+** > Approvals > add the real approver(s).

This is the one part that genuinely can't be expressed in YAML. Until it's configured, the pipeline is still gated - `pipelines/templates/deploy-and-rollback.yml` has its own `ManualValidation@0` step before the deploy runs, and a second, separate one before any rollback runs. The Environment approval above is an additional, portal-managed layer on top of that, not a replacement for it.

### 4. Approver email
Replace `REPLACE_ME_PROD_APPROVERS@yourcompany.com` in `pipelines/templates/deploy-and-rollback.yml` (it appears twice - once for the deploy approval, once for the rollback approval) with the real approver email(s)/group.

### 5. Container registry
Fill in `containerRegistryServiceConnection` and `containerRegistryLoginServer` in `azure-pipelines.yml` to point at your real ACR (or equivalent) instance.

### 6. SonarQube
Create the project (key must match `sonarQubeProjectKey`), and confirm a Quality Gate is attached to it - the default gate is fine to start with. `SonarQubePublish@5` polls this gate and fails the pipeline if it's red.

### 7. AKS cluster
Fill in `aksResourceGroup` and `aksClusterName` in `azure-pipelines.yml`.

### 8. Helm chart values
Everything in `helm/car-booking-service/values.yaml` marked `REPLACE_ME`: the real container registry, Kafka bootstrap servers, credit-card-service URL, DB host, and the workload identity client ID (only needed if using Azure Key Vault CSI driver instead of a plain Secret).

## What you get once all of the above is filled in

```
push to main
  -> Build (compile, full test suite against real Postgres/Kafka via Testcontainers, JaCoCo coverage)
  -> CodeQuality (SonarQube analysis + quality gate) ─┐
  -> SecurityScan (OWASP dep-check, gitleaks, SAST placeholder) ─┘  (run in parallel)
  -> PackageAndPublish (Docker build -> Trivy scan -> push image; Helm lint & package)
  -> ApproveDeploy      (ManualValidation - a human reviews the build before anything touches production)
  -> DeployProduction   (helm upgrade --install, then a smoke test)
  -> ApproveRollback    (only reached if DeployProduction failed - a healthy deploy skips straight past this)
  -> RollbackProduction (only reached if ApproveRollback was actually approved)
```

A rollback is never automatic and never silent: it only gets *offered* when a deployment genuinely fails, and even then a second human has to say yes before it runs.
