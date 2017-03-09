#!/bin/bash

## Runs a COMMAND on multiple hosts
## Using -oStrictHostKeyChecking=no to prevent interactivity in ssh

# Default is edg-perf lab, lower cluster and perf-test.sh
CLUSTER="edg-perf"
SUFFIX="01 02 03 04 05 06 07 08"
#COMMAND="perf-test.sh"
COMMAND="sudo su jgroups -c '/opt/jgroups/IspnPerfTest/bin/aws.sh -nohup &> /tmp/IspnPerfTest.log' &"

#### Replace this with own private key!!!
PK=$HOME/.aws/bela.pem

#### Set if a different user is required for SSH
USER=ec2-user

SSH_OPTS="-i $PK -oStrictHostKeyChecking=no -oUserKnownHostsFile=/dev/null"

show_help() {
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
	echo "--uperf      -p uperf.sh"
	exit 0;
}

#parse parameters
while true; do
  case "$1" in
    -h | --help ) show_help;;
    -a | --all ) SUFFIX="01 02 03 04 05 06 07 08 09 10 11 12 13 14 15 16"; shift ;;
    -u | --upper ) SUFFIX="09 10 11 12 13 14 15 16"; shift ;;
    -l | --lower ) SUFFIX="01 02 03 04 05 06 07 08"; shift ;;
    -e | --edg-perf ) CLUSTER="edg-perf"; shift ;;
    -c | --cluster ) CLUSTER="cluster"; shift ;;
    -hc | --hazelcast ) COMMAND="hc-perf-test.sh"; shift ;;
    -o | --coherence )  COMMAND="coh-perf-test.sh"; shift ;;
    -i | --infinispan ) COMMAND="perf-test.sh"; shift ;;
    -t | --tri )        COMMAND="tri-perf-test.sh"; shift ;;
    -p | --uperf )      COMMAND="uperf.sh"; shift ;;
    -n | --nodes )
          nodes=""
          while [[ ${2:0:1} != "-" ]];
             do
                 if [[ -z $2 ]]; then break; fi;
                 nodes="$nodes $2";
                 shift;
          done;
          shift;
          ;;
    * ) break;;
  esac
done

SCRIPT_DIR="`pwd`/`dirname $0`"

if [[ -z $nodes ]];
then
    for i in $SUFFIX;
      do nodes="$nodes ${CLUSTER}${i}"
    done
fi

nodes=$(echo $nodes | tr -s " " " ")
first=$(echo $nodes | cut -d' ' -f1)
rest=$(echo $nodes | cut -d' ' -f2-)
echo "** nodes: $nodes, first: $first, rest: $rest"


#run on all nodes except the first one
for i in ${rest}; do
  echo "run on ${i}";
  echo "SSH_OPTS: $SSH_OPTS"
  ssh $SSH_OPTS -f $USER@${i} "nohup ${SCRIPT_DIR}/${COMMAND} -nohup > /tmp/log < /dev/null &";
done

#run on first node (09 for upper cluster), this will be the control node
ssh $SSH_OPTS $USER@${first} "${SCRIPT_DIR}/${COMMAND}";
