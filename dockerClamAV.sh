#!/bin/bash

ARG=$1

CONTAINER_NAME="clammy"

function clean {
  echo "Removing ClamAV container..."
  docker rm $CONTAINER_NAME
}

function stop {
  echo "Stopping ClamAV container..."
  docker stop $CONTAINER_NAME
  echo "ClamAV container stopped."
}

function status {
  CLAMMY_EXISTS=$( docker ps --quiet --filter name=clammy )

  if [[ -n "$CLAMMY_EXISTS" ]]; then
    echo -e "ClamAV container status:       \033[1;32m up\033[0;0m"
  else
    echo -e "ClamAV container status:       \033[1;31m down\033[0;0m"
  fi
}

function start {
  CLAMMY_EXISTS=$( docker ps --quiet --filter name=clammy )

  if [[ -n "$CLAMMY_EXISTS" ]]; then
    echo "Starting ClamAV docker container..."
    docker start $CONTAINER_NAME
  else
    docker run --name $CONTAINER_NAME -d -p 3310:3310 kpmeen/docker-clamav
  fi

  echo "To tail the log of ClamAV run the following command:\n"
  echo "   docker exec -it $CONTAINER_NAME tail -300f /var/log/clamav/clamav.log\n"
}


function reset {
  stop
  clean
  start
}

if [ "$ARG" == "start" ]; then
  start
  status

elif [ "$ARG" == "stop" ]; then
  stop
  status

elif [ "$ARG" == "clean" ]; then
  stop
  clean

elif [ "$ARG" == "reset" ]; then
  reset
  status

elif [ "$ARG" == "status" ]; then
  status
fi
