#!/bin/bash

## Kills all Java processes for a given user on the hosts listed below

CLUSTER=edg-perf

for i in 01 02 03 04 05 06 07 08 09 10 11 12 13 14 15 16;
  do ssh -f ${CLUSTER}${i} "killall java";
done