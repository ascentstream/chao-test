#!/usr/bin/env bash
BASE_DIR=$(cd $(dirname $0); pwd)
echo  $BASE_DIR
export ASP_NAMESPACE=pulsar-cluster
app_root_path=${APP_ROOT_PATH:-"/app/chao-test-1.0-SNAPSHOT"}
kafka_bootstrap_servers=${KAFKA_BOOTSTRAP_SERVERS:-"pulsar-asp-broker-headless:9092"}
send_msg_count=${SEND_MSG_COUNT:-"50000"}
max_waiting_time=${MAX_WAITING_TIME:-"600"}
topic_name=${TOPIC:-"at-least-once"}
topic_partition=${TOPIC_PARTITION:-"10"}
main_class=${MAIN_CLASS:-"com.ascentsream.tests.kop.AtLeastOnceMessaging"}

spec='
{
  "spec": {
    "containers": [
      {
        "name": "chao-test",
        "image": "chao-test:latest",
        "imagePullPolicy": "Never",
        "command": ["/bin/sh", "-c", "cd {{app_root_path}}/bin && exec sh runserver.sh -Dkafka.bootstrap.servers={{kafka_bootstrap_servers}} -Dsend.msg.count={{send_msg_count}} -Dtopic={{topic_name}} -Dkafka.group.id=group-1 -Dmax.waiting.time={{max_waiting_time}} -Dtopic.partition={{topic_partition}} {{main_class}}"],
       "volumeMounts": [
          {
            "name": "host-path",
            "mountPath": "/data"
          }
          ]
        }
      ],
     "volumes": [
           {
             "name": "host-path",
             "hostPath": {
             "path": "/kind/chao-test/data"
             }
           }
         ]
       }
  }'

replace_string() {
    original_string="$1"
    to_replace="$2"
    replace_with="$3"
    updated_string=$(echo "$original_string" | sed "s|${to_replace}|${replace_with}|g")
    echo "$updated_string"
}


spec=$(replace_string "$spec" "{{app_root_path}}" "$app_root_path")
spec=$(replace_string "$spec" "{{kafka_bootstrap_servers}}" "$kafka_bootstrap_servers")
spec=$(replace_string "$spec" "{{send_msg_count}}" "$send_msg_count")
spec=$(replace_string "$spec" "{{max_waiting_time}}" "$max_waiting_time")
spec=$(replace_string "$spec" "{{topic_partition}}" "$topic_partition")
spec=$(replace_string "$spec" "{{main_class}}" "$main_class")
spec=$(replace_string "$spec" "{{topic_name}}" "$topic_name")

echo "$spec"
kubectl -n ${ASP_NAMESPACE} run chao-test --image=chao-test:latest --restart=Never --overrides="$spec"