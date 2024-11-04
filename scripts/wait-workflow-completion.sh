#!/usr/bin/env bash
BASE_DIR=$(cd $(dirname $0); pwd)
echo  $BASE_DIR

RUNNING_DIR=$BASE_DIR/running

export ASP_NAMESPACE=pulsar-cluster

check_pod_status() {
    pod_name=$1

    pod_exists=$(kubectl get pod $pod_name -n $ASP_NAMESPACE 2>/dev/null)

    if [ -z "$pod_exists" ]; then
        echo "Pod $pod_name not exit！"
        exit 1
    fi

    while true; do
        POD_STATUS=$(kubectl get pod $pod_name -o jsonpath='{.status.phase}' -n $ASP_NAMESPACE)

        if [ "$POD_STATUS" = "Succeeded" ]; then
            break
        fi

        pod_ready=$(kubectl get pod $pod_name -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' -n $ASP_NAMESPACE)
        if [ "$pod_ready" == "True" ];  then
            break
        else
            kubectl get pod  -n ${ASP_NAMESPACE} | grep "chao-test"
            sleep 5
        fi
    done
}

pod_name="chao-test"
check_pod_status $pod_name
kubectl -n $ASP_NAMESPACE logs -f $pod_name
RESULT=$(kubectl -n $ASP_NAMESPACE logs chao-test | grep "chao test result :")
echo $RESULT
VALUE=$(echo "$RESULT" | awk -F':' '{print tolower($NF)}' | xargs)
if [ "$VALUE" = "true." ]; then
    echo "success!"
else
    echo "fialed!"
    exit 1
fi
