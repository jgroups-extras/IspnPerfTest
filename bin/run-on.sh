#!/bin/bash

## Runs a COMMAND on multiple hosts

# Default is edg-perf lab, lower cluster and perf-test.sh
CLUSTER="edg-perf"
NODES="01 02 03 04 05 06 07 08"
COMMAND="perf-test.sh"

show_help(){
	echo "Runs a perf test on specified hosts"
	echo "Usage:"
	echo "run-on.sh [--all --upper --lower --cluster --edg-perf --hazelcast --coherence --infinispan]"
	echo "defaults to run-on.sh --lower --edg-perf"
	echo "--all -a 01 02 03 04 05 06 07 08 09 10 11 12 13 14 15 16"
	echo "--lower -l 01 02 03 04 05 06 07 08"
	echo "--upper -u 09 10 11 12 13 14 15 16"
	echo "--cluster -c  cluster"
	echo "--edg-perf -e edg-perf"
	echo "--hazelcast -hc hc-perf-test.sh"
	echo "--coherence -o coh-perf-test.sh"
	echo "--infinispan -i perf-test.sh"
	echo "--tri        -t tri-perf-test.sh"
	echo "--uperf      -u uperf.sh"
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
    -hc | --hazelcast ) COMMAND="hc-perf-test.sh"; shift ;;
    -o | --coherence ) COMMAND="coh-perf-test.sh"; shift ;;
    -i | --infinispan ) COMMAND="perf-test.sh"; shift ;;
    -t | --tri )        COMMAND="tri-perf-test.sh"; shift ;;
    -p | --uperf )      COMMAND="uperf.sh"; shift ;;
    * ) break;;
  esac
done

SCRIPT_DIR="`pwd`/`dirname $0`"

#run on all nodes except the first one
for i in ${NODES[@]:2}; do
  echo "run on ${CLUSTER}${i}";
  ssh -f ${CLUSTER}${i} "nohup ${SCRIPT_DIR}/${COMMAND} -nohup > /tmp/log < /dev/null &";
done

#run on first node (09 for upper cluster), this will be the control node
ssh $CLUSTER${NODES[@]:0:2} "${SCRIPT_DIR}/${COMMAND}";
