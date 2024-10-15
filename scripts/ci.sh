#!/usr/bin/env bash

set -e

readonly CURRENT_PATH=$(cd "$(dirname "$0")";pwd)
readonly PLATFORM_IMAGE_TAG=${AS_PLATFORM_IMAGE_TAG}


function ci_deploy_as_platform() {
  pushd "$REPO_PATH"
  $REPO_PATH/scripts/asp-deploy.sh install ${PLATFORM_IMAGE_TAG} ${REGISTRY_USERNAME} ${REGISTRY_PASSWORD}
  popd
}



if [ -z "$1" ]; then
  echo "usage: $0 [ci_tool_function_name]"
  echo "Available ci tool functions:"
  list_functions
  exit 1
fi

"ci_$1"
