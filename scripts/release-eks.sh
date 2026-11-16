#!/usr/bin/env bash
# Run from this repository's root. AWS credentials come from the standard CLI chain.
set -euo pipefail
set +x
export AWS_PAGER=""
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
required() { [[ -n "${!1:-}" ]] || fail "Set $1"; }
for name in SERVICE AWS_REGION AWS_ACCOUNT_ID ECR_REPOSITORY RELEASE_ID; do required "$name"; done
DEPLOY=${DEPLOY:-false}
K8S_NAMESPACE=${K8S_NAMESPACE:-store}
IMAGE_PLATFORM=${IMAGE_PLATFORM:-linux/amd64}
[[ "$SERVICE" =~ ^(product|order|exchange)$ ]] || fail 'Invalid SERVICE'
[[ "$AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || fail 'AWS_ACCOUNT_ID must contain 12 digits'
[[ "$AWS_REGION" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]+$ ]] || fail 'Invalid AWS_REGION'
[[ "$ECR_REPOSITORY" =~ ^[a-z0-9]+([._/-][a-z0-9]+)*$ ]] || fail 'Invalid ECR_REPOSITORY'
[[ "$RELEASE_ID" =~ ^[0-9]+-[0-9]+$ ]] || fail 'RELEASE_ID must be run_id-run_attempt'
[[ "$DEPLOY" == true || "$DEPLOY" == false ]] || fail 'DEPLOY must be true or false'
[[ "$IMAGE_PLATFORM" == linux/amd64 || "$IMAGE_PLATFORM" == linux/arm64 ]] || fail 'Unsupported IMAGE_PLATFORM'
for tool in aws docker git python3; do command -v "$tool" >/dev/null || fail "Install $tool on the runner"; done
if [[ "$DEPLOY" == true ]]; then
  required EKS_CLUSTER_NAME
  [[ "$EKS_CLUSTER_NAME" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] || fail 'Invalid EKS_CLUSTER_NAME'
  [[ ${#K8S_NAMESPACE} -le 63 && "$K8S_NAMESPACE" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || fail 'Invalid K8S_NAMESPACE'
  command -v kubectl >/dev/null || fail 'Install kubectl on the runner'
fi

actual_account=$(aws sts get-caller-identity --query Account --output text)
[[ "$actual_account" == "$AWS_ACCOUNT_ID" ]] || fail 'Runner AWS identity belongs to another account'
repository_uri=$(aws ecr describe-repositories --region "$AWS_REGION" --repository-names "$ECR_REPOSITORY" --query 'repositories[0].repositoryUri' --output text)
registry=${repository_uri%%/*}
[[ "$registry" == "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com" || "$registry" == "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com.cn" ]] || fail 'Unexpected ECR registry'
[[ "$repository_uri" == "$registry/$ECR_REPOSITORY" ]] || fail 'Unexpected ECR repository URI'
source_commit=$(git rev-parse HEAD)
[[ "$source_commit" =~ ^[0-9a-f]{40}$ ]] || fail 'Expected a Git SHA-1 commit'
image_tag="$source_commit-$RELEASE_ID"
image="$repository_uri:$image_tag"

umask 077
release_tmp=$(mktemp -d)
trap 'rm -rf "$release_tmp"' EXIT
export DOCKER_CONFIG="$release_tmp/docker"
mkdir -p "$DOCKER_CONFIG" artifacts
if [[ "$DEPLOY" == true ]]; then
  export KUBECONFIG="$release_tmp/kubeconfig"
  aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" --kubeconfig "$KUBECONFIG" --alias release >/dev/null
  kube=(kubectl --namespace="$K8S_NAMESPACE" --request-timeout=30s)
  # Only check names. Never print Secret values in CI logs or artifacts.
  "${kube[@]}" get configmap "$SERVICE-config" -o name >/dev/null
  "${kube[@]}" get secret "$SERVICE-secrets" -o name >/dev/null
fi

docker build --platform "$IMAGE_PLATFORM" --label "org.opencontainers.image.revision=$source_commit" -t "$image" .
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$registry"
docker push "$image"
digest=$(aws ecr describe-images --region "$AWS_REGION" --repository-name "$ECR_REPOSITORY" --image-ids "imageTag=$image_tag" --query 'imageDetails[0].imageDigest' --output text)
[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 'ECR did not return an image digest'
image_digest="$repository_uri@$digest"
python3 - "$image" "$image_digest" "$source_commit" "$DEPLOY" <<'PY'
import json, sys
from pathlib import Path
Path('artifacts/release.json').write_text(json.dumps(dict(
    image_tag=sys.argv[1], image_digest=sys.argv[2], commit=sys.argv[3],
    deployment='pending' if sys.argv[4] == 'true' else 'not_requested'), indent=2) + '\n')
PY
printf 'Published %s\n' "$image_digest"
if [[ "$DEPLOY" != true ]]; then exit 0; fi

# Kustomize runs locally; environment ConfigMaps and Secrets are NOT reapplied.
cp k8s/k8s.yaml "$release_tmp/application.yaml"
python3 - "$release_tmp" "$SERVICE" "$K8S_NAMESPACE" "$repository_uri" "$digest" "${IMAGE_PLATFORM#linux/}" <<'PY'
import json, sys
from pathlib import Path
directory, service, namespace, repository, digest, arch = sys.argv[1:]
manifest = {
    'apiVersion': 'kustomize.config.k8s.io/v1beta1', 'kind': 'Kustomization',
    'resources': ['application.yaml'], 'namespace': namespace,
    'images': [{'name': f'store/{service}-service', 'newName': repository, 'digest': digest}],
    'patches': [{'target': {'kind': 'Deployment', 'name': service}, 'patch': json.dumps([
        {'op': 'add', 'path': '/spec/template/spec/nodeSelector',
         'value': {'kubernetes.io/os': 'linux', 'kubernetes.io/arch': arch}}])}]
}
Path(directory, 'kustomization.yaml').write_text(json.dumps(manifest))
PY
kubectl kustomize "$release_tmp" > artifacts/deployment.yaml
"${kube[@]}" apply --dry-run=server -f artifacts/deployment.yaml >/dev/null
"${kube[@]}" apply -f artifacts/deployment.yaml
if ! "${kube[@]}" rollout status "deployment/$SERVICE" --timeout=300s --request-timeout=330s; then
  "${kube[@]}" get pods -l "app=$SERVICE" -o wide || true
  fail 'Rollout failed; inspect the workload before retrying or rolling back'
fi
python3 - <<'PY'
import json
from pathlib import Path
path = Path('artifacts/release.json')
release = json.loads(path.read_text())
release['deployment'] = 'succeeded'
path.write_text(json.dumps(release, indent=2) + '\n')
PY
