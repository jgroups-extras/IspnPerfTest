#!/bin/bash

DIR=`dirname $0`
export raft_id="$1"; shift;
export JAVA_OPTS="-Draft_id=$raft_id $JAVA_OPTS"

export proc_id=$$

$DIR/run.sh org.perf.Test -factory raft -cfg jgroups-tcp-raft.xml $*
