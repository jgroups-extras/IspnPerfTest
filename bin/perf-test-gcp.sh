#!/bin/bash

DIR=`dirname $0`
$DIR/run.sh org.perf.Test -cfg dist-sync-gcp.xml -control-cfg control-gcp.xml $*
