#!/bin/bash

DIR=`dirname $0`
$DIR/perf-test.sh -cfg dist-sync-gcp.xml -control-cfg control-gcp.xml
