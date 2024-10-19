#!/usr/bin/env bash
BASE_DIR=$(cd $(dirname $0); pwd)
echo  $BASE_DIR
export ASP_NAMESPACE=pulsar-cluster

pod_name="chao-test"

node_name=$(kubectl -n $ASP_NAMESPACE get pod $pod_name -o wide | awk 'NR==2{print $7}')
echo "Pod $pod_name deploy Node $node_name 上。"

container_id=$(docker ps -aqf "name=$node_name")
if [ -z "$container_id" ]; then
    echo "connot find $node_name container."
else
    echo "container $node_name id is $container_id"
fi

docker cp $container_id:/kind/chao-test/logs/  $1
