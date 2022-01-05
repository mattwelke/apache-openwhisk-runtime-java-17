#!/bin/env bash

# A build script for the experiment for actions implementing abstract classes.

SECONDS_SINCE_EPOCH=$(printf '%(%s)T\n' -1)

TAG="owextendedruntimes/java-17:experiment-abstract-class-impl-${SECONDS_SINCE_EPOCH}"

docker build -t $TAG .
docker push $TAG
