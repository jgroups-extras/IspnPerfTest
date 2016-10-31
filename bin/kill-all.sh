#!/bin/bash

## Kills all Java processes for a given user on the hosts listed below

CLUSTER=edg-perf
COMMAND=perf-test.sh

for i in 01 02 03 04 05 06 07 08;
  do ssh -f ${CLUSTER}${i} "killall java";
done