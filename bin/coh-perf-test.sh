#!/bin/bash


# Author: Bela Ban

conf_dir=`dirname $0`/../conf

export proc_id=$$

export CONFIG="-Dtangosol.coherence.cacheconfig=$conf_dir/coh.xml"


`dirname $0`/perf-test.sh -cfg $conf_dir/coh.xml -factory coh $*

