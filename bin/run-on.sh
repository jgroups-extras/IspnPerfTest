#!/bin/bash

## Runs a command on multiple hosts

CLUSTER=edg-perf
COMMAND=perf-test.sh

for i in 02 03 04 05 06 07 08;
  do ssh -f ${CLUSTER}${i} "nohup IspnPerfTest/bin/${COMMAND} -nohup 2> /tmp/log < /dev/null &";
done