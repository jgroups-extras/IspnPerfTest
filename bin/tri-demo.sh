#!/bin/bash

DIR=`dirname $0`
$DIR/run.sh org.perf.Demo -factory tri -cfg jgroups-tcp.xml $*

