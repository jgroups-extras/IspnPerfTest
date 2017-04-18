#!/bin/bash

conf_dir=`dirname $0`/../conf

`dirname $0`/perf-test.sh -cfg $conf_dir/jgroups-aws.xml -jgroups-cfg $conf_dir/control-aws.xml -factory dummy $*
