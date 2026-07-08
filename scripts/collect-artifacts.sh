#!/usr/bin/env bash
#
# Collect diagnostic artifacts (logs, optional heap dumps) from the
# pulsar-cluster namespace and bundle them into artifacts-<test>.zip.
#
# Usage:
#   collect-artifacts.sh <test-name> [with-heap-dump]
#
# Arguments:
#   test-name        - name of the matrix case, used in the output zip filename
#   with-heap-dump   - optional, "true" to also capture jmap heap dumps
#                      from broker pods (used by kop-test / kop-test-per-hour)
#
# Environment:
#   REPO_PATH        - repository root (to locate cp-logs-from-kind.sh)

set -u

TEST_NAME=${1:?usage: collect-artifacts.sh <test-name> [with-heap-dump]}
WITH_HEAP=${2:-false}
NAMESPACE=pulsar-cluster
CURRENT_DIR=$PWD

if [ -z "${REPO_PATH:-}" ]; then
  echo "REPO_PATH is not set; falling back to \$(pwd)/.."
  REPO_PATH="$(cd "$(dirname "$0")/.." && pwd)"
fi

echo "=== Collecting artifacts for test: $TEST_NAME (heap-dump: $WITH_HEAP) ==="

rm -rf artifacts
mkdir -p artifacts/chao-test artifacts/pulsar

# 1. chao-test pod logs + anything cp-logs-from-kind.sh pulls out of kind
kubectl -n "$NAMESPACE" logs chao-test > "$CURRENT_DIR/artifacts/chao-test/chao-test.log" 2>&1 || true
if [ -x "$REPO_PATH/scripts/cp-logs-from-kind.sh" ]; then
  "$REPO_PATH/scripts/cp-logs-from-kind.sh" "$CURRENT_DIR/artifacts/chao-test" || true
fi

# 2. Per-pod logs for broker / bookie / zookeeper via label selector
#    (resilient to replica count changes - was previously hardcoded to
#     broker-0/1, bookie-0/1/2, zookeeper-0/1/2)
LOG_FLAGS=""
if [ "$WITH_HEAP" == "true" ]; then
  # kop-test / per-hour use --since=0 to capture the full log buffer
  LOG_FLAGS="--since=0"
fi

for role in broker bookie zookeeper; do
  while IFS= read -r pod; do
    [ -z "$pod" ] && continue
    pod_name=${pod#pod/}
    echo "  logs: $pod_name"
    kubectl -n "$NAMESPACE" logs "$pod_name" $LOG_FLAGS \
      > "$CURRENT_DIR/artifacts/pulsar/$pod_name.log" 2>&1 || true
  done < <(kubectl -n "$NAMESPACE" get pods \
            -l "cluster=pulsar-asp,component=${role},!job-name" \
            -o name 2>/dev/null)
done

# 3. Optional heap dumps from broker pods (kop-test / per-hour)
if [ "$WITH_HEAP" == "true" ]; then
  while IFS= read -r pod; do
    [ -z "$pod" ] && continue
    pod_name=${pod#pod/}
    heap_path="/pulsar/heap-${pod_name}.bin"
    echo "  heap dump: $pod_name"
    kubectl -n "$NAMESPACE" exec "$pod_name" -- \
      jmap -dump:live,format=b,file="$heap_path" 1 || true
    kubectl cp "$NAMESPACE/${pod_name}:${heap_path}" \
      "$CURRENT_DIR/artifacts/pulsar/heap-${pod_name}.bin" || true
  done < <(kubectl -n "$NAMESPACE" get pods -l "cluster=pulsar-asp,component=broker" -o name 2>/dev/null)
fi

# 4. Zip everything for upload-artifact
zip -r "artifacts-${TEST_NAME}.zip" artifacts >/dev/null
echo "=== Built artifacts-${TEST_NAME}.zip ==="
