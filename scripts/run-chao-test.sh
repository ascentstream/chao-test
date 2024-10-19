#!/usr/bin/env bash
BASE_DIR=$(cd $(dirname $0); pwd)
echo $BASE_DIR
export ASP_NAMESPACE=pulsar-cluster


kubectl -n ${ASP_NAMESPACE} run chao-test --image=chao-test:latest --restart=Never --overrides='
{
  "spec": {
    "containers": [
      {
        "name": "chao-test",
        "image": "chao-test:latest",
        "imagePullPolicy": "Never",
        "command": ["/bin/sh", "-c", "cd /app/chao-test-1.0-SNAPSHOT/bin && exec sh runserver.sh com.ascentsream.tests.kop.AtLeastOnceMessaging"],
        "volumeMounts": [
          {
            "name": "host-log-path",
            "mountPath": "/app/chao-test-1.0-SNAPSHOT/logs"
          },
          {
            "name": "host-test-data-path",
            "mountPath": "/tmp/chao_test"
          }
        ]
      }
    ],
    "volumes": [
      {
        "name": "host-log-path",
        "hostPath": {
          "path": "/kind/chao-test/logs"
        },
        {
        "name": "host-test-data-path",
        "hostPath": {
          "path": "/kind/chao-test/data"
        }
        }
      }
    ]
  }
}'