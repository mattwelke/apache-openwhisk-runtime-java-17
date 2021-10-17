#!/bin/env bash

SECONDS_SINCE_EPOCH=$(printf '%(%s)T\n' -1)

TAG=$SECONDS_SINCE_EPOCH

docker build -t owextendedruntimes/java-17:$TAG .
