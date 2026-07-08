#!/usr/bin/env bash
BASE_DIR=$(cd $(dirname $0); pwd)
echo  $BASE_DIR

RUNNING_DIR=$BASE_DIR/running

PLATFORM_IMAGE_TAG="$2"
REGISTRY_USERNAME="$3"
REGISTRY_PASSWORD="$4"

export ASO_NAMESPACE=pulsar-operator 
export ASP_NAMESPACE=pulsar-cluster
export ASO_RELEASE_NAME=asp-operator
export CM_RELEASE_NAME=cert-manager
export ASP_RELEASE_NAME=pulsar

check_pod_status() {
    pod_name=$1
    max_checks=120
    interval=5
    count=0

    # Phase 1: wait for the pod to be created (operator may not have
    # created the StatefulSet yet, e.g. while broker becomes ready or
    # metadata init is still running). Previously this exited immediately
    # if the pod was missing on the first check.
    echo "Waiting for pod $pod_name to be created..."
    while ! kubectl get pod $pod_name -n $ASP_NAMESPACE >/dev/null 2>&1; do
        kubectl get pod -n ${ASP_NAMESPACE} 2>/dev/null
        sleep $interval
        count=$((count + 1))
        if [ $count -eq $max_checks ]; then
            echo "Pod $pod_name was not created within $((max_checks * interval))s. Exiting."
            kubectl get pod -n ${ASP_NAMESPACE}
            exit 1
        fi
    done

    echo "Pod $pod_name created. Waiting for Ready..."
    count=0
    while true; do
        pod_ready=$(kubectl get pod $pod_name -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' -n $ASP_NAMESPACE)
        if [ "$pod_ready" == "True" ]; then
            kubectl get pod -n ${ASP_NAMESPACE}
            break
        else
            kubectl get pod -n ${ASP_NAMESPACE}
            sleep $interval
            count=$((count + 1))
            if [ $count -eq $max_checks ]; then
                echo "Pod $pod_name did not become Ready within $((max_checks * interval))s."
                echo "=== describe ==="
                kubectl -n $ASP_NAMESPACE describe pod $pod_name | tail -60
                echo "=== init container logs ==="
                kubectl -n $ASP_NAMESPACE logs $pod_name --all-containers --init-containers --tail=100
                exit 1
            fi
        fi
    done
}


function install()
{
    echo "install Starting ..."
    echo "create namespace ${ASO_NAMESPACE} ${ASP_NAMESPACE}"
    kubectl create namespace ${ASO_NAMESPACE}
    kubectl create namespace ${ASP_NAMESPACE}

    echo "create image pull secret"
    kubectl create secret docker-registry regcred --docker-server=https://index.docker.io/v1/ --docker-username=${REGISTRY_USERNAME} --docker-password=${REGISTRY_PASSWORD} --docker-email=bot@ascentstream.com -n ${ASO_NAMESPACE}
    kubectl create secret docker-registry regcred --docker-server=https://index.docker.io/v1/ --docker-username=${REGISTRY_USERNAME} --docker-password=${REGISTRY_PASSWORD} --docker-email=bot@ascentstream.com -n ${ASP_NAMESPACE}

    echo "installing asp-operator"
    helm install ${ASO_RELEASE_NAME} ${RUNNING_DIR}/chart/asp-operator-2.0.7.tgz -n ${ASO_NAMESPACE} --values ${RUNNING_DIR}/chart/aso-values.yaml --create-namespace
    kubectl get pod  -n ${ASO_NAMESPACE}
    echo "installed asp-operator"

    echo "installing cert-manager"
    kubectl apply -f ${RUNNING_DIR}/chart/cert-manager.crds.yaml
    helm install ${CM_RELEASE_NAME} ${RUNNING_DIR}/chart/cert-manager-v1.12.1.tgz --create-namespace -n ${ASO_NAMESPACE} --values ${RUNNING_DIR}/chart/cm-values.yaml
    kubectl get pod  -n ${ASO_NAMESPACE}
    echo "installed cert-manager"

    echo "installing as-plartform"
    helm install ${ASP_RELEASE_NAME} ${RUNNING_DIR}/chart/asp-2.0.2.tgz \
        --set initialize=true \
        --set namespace=${ASP_NAMESPACE} \
        --set global.aspImageTag=${PLATFORM_IMAGE_TAG} \
        --create-namespace -n ${ASP_NAMESPACE} \
        --values ${RUNNING_DIR}/chart/asp-values.yaml
    kubectl get pod  -n ${ASP_NAMESPACE} 
    echo "installed as-plartform"

    check_pod_status "pulsar-asp-proxy-0"

    echo "installed success! "
}

function uninstall()
{
    echo "uninstall  Starting..."

    echo "uninstalling as-plartform"
    helm uninstall ${ASP_RELEASE_NAME}  -n ${ASP_NAMESPACE}
    echo "uninstalled as-plartform"

    echo "uninstalling cert-manager"
    kubectl delete -f ${RUNNING_DIR}/chart/cert-manager.crds.yaml
    helm uninstall ${CM_RELEASE_NAME}  -n ${ASO_NAMESPACE}
    echo "uninstalled cert-manager"

    echo "uninstalling as-plartform"
    helm uninstall ${ASO_RELEASE_NAME}  -n ${ASO_NAMESPACE}
    echo "uninstalled as-plartform"

    echo "delete image pull secret"
    kubectl delete secret regcred -n ${ASP_NAMESPACE}
    kubectl delete secret regcred -n ${ASO_NAMESPACE}

    echo "delete namespace ${ASO_NAMESPACE} ${ASP_NAMESPACE}"
    kubectl delete namespace pulsar-cluster  
    kubectl delete namespace pulsar-operator
    echo "uninstall success! "
}

function init_env()
{
  mkdir -p ${BASE_DIR}/running/chart
  cp -f ${BASE_DIR}/../deploy/chart/asp-platform/* ${BASE_DIR}/running/chart
}


init_env
case $1 in
install)
    install;
    ;;
uninstall)
    uninstall;
    exit 0
    ;;
*)
  echo "Usage: $0 {install|uninstall} env"
esac
