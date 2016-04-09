#!/bin/bash


# Author: Bela Ban

conf_dir=`dirname $0`/../conf

export proc_id=$$

## Change localhost to point to the bind address to be used by Coherence
export CONFIG="-Dtangosol.coherence.cacheconfig=$conf_dir/coh.xml -Dtangosol.coherence.localhost=127.0.0.1"


`dirname $0`/perf-test.sh -cfg $conf_dir/coh.xml -factory coh $*

