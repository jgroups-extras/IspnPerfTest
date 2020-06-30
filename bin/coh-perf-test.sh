#!/bin/bash

# Author: Bela Ban


export proc_id=$$

## Change localhost to point to the bind address to be used by Coherence
export CONFIG="-Dtangosol.coherence.cacheconfig=$conf_dir/coh.xml -Dtangosol.coherence.localhost=127.0.0.1"
export CONFIG="$CONFIG -Dtangosol.coherence.distributed.localstorage=true -Dstorage_enabled=true -Dcoherence.distributed.localstorage=true"


exec `dirname $0`/perf-test.sh -cfg coh.xml -factory coh $*

