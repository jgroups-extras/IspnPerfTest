#!/bin/bash

exec `dirname $0`/perf-test.sh -cfg jgroups-aws.xml -jgroups-cfg control-aws.xml -factory dummy $*
