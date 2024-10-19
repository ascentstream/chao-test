#!/usr/bin/env bash
BASE_DIR=$(cd $(dirname $0); pwd)
echo  $BASE_DIR
export ASP_NAMESPACE=pulsar-cluster

kubectl -n ${ASP_NAMESPACE} run chao-test --image=chao-test:latest --restart=Never --overrides='
{
  "spec": {
    "containers": [
      {
        "name": "chao-test",
        "image": "chao-test:latest",
        "imagePullPolicy": "Never",
        "command": ["/bin/sh", "-c", "cd /app/chao-test-1.0-SNAPSHOT/bin && exec sh runserver.sh -Dkafka.bootstrap.servers=pulsar-asp-broker-headless:9092 -Dsend.msg.count=50000 -Dtopic=at-least-once -Dkafka.group.id=group1 com.ascentsream.tests.kop.AtLeastOnceMessaging"],
        "volumeMounts": [
          {
            "name": "host-path",
            "mountPath": "/app/chao-test-1.0-SNAPSHOT/logs"
          }
        ]
      }
    ],
    "volumes": [
      {
        "name": "host-path",
        "hostPath": {
        "path": "/kind/chao-test/logs"
        }
      }
    ]
  }
}'