#!/bin/bash

## Kills Test processes for a given user specified hosts

# Default is edg-perf lab and lower cluster
CLUSTER="edg-perf"
NODES="01 02 03 04 05 06 07 08"

show_help(){
	echo "Kills Test processes for a given user on specified hosts"
	echo "Usage:"
	echo "kill-all.sh [--all --upper --lower --cluster --edg-perf]"
	echo "defaults to kill-all.sh --lower --edg-perf"
	echo "--all -a 01 02 03 04 05 06 07 08 09 10 11 12 13 14 15 16"
	echo "--lower -l 01 02 03 04 05 06 07 08"
	echo "--upper -u 09 10 11 12 13 14 15 16"
	echo "--cluster -c  cluster"
	echo "--edg-perf -e edg-perf"
	exit 0;
}

#parse parameters
while true; do
  case "$1" in
    -h | --help ) show_help;;
    -a | --all ) NODES="01 02 03 04 05 06 07 08 09 10 11 12 13 14 15 16"; shift ;;
    -u | --upper ) NODES="09 10 11 12 13 14 15 16"; shift ;;
    -l | --lower ) NODES="01 02 03 04 05 06 07 08"; shift ;;
    -e | --edg-perf ) CLUSTER="edg-perf"; shift ;;
    -c | --cluster ) CLUSTER="cluster"; shift ;;
    * ) break;;
  esac
done

for i in $NODES; do
   echo "kill ${CLUSTER}${i}";
   ssh -f ${CLUSTER}${i} 'kill `jps | grep -e Test | cut -f 1 -d " "`';
done
