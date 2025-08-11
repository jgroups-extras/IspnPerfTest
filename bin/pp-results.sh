
## Pretty-print results.csv

#!/bin/bash

if [ $# -lt 1 ];
    then echo "pp-results.sh <results.csv>";
         exit 1
fi

echo $1

RESULTS_FILE=$1

cat $RESULTS_FILE | sed "s/-Dorg.infinispan.threads.virtual=True -Dvthreads=True //g" |
    sed "s/jg-vthreads=true//g" | sed "s/ispn-vthreads=true//g" |
    sed "s/.Final//g" | sed "s/-D//g" | sed "s/env-props=//g" |
    sed "s/jgroups.bundler.//g" | sed "s/bundler.//g" | sed "s/use_//g" |
    sed "s/single_sender_thread/sst/g" | sed "s/ringbuffer/rb/g" |
    sed "s/rb=true/+rb/g" | sed "s/rb=false/-rb/g" |
    sed "s/sst=true/+sst/g" | sed "s/sst=false/-sst/g" | sed "s/type=//g" |
    sed "s/jdk=21[^[:blank:]]*/jdk=21/" | sed "s/jdk=25[^[:blank:]]*/jdk=25/" |
    sed "s/jdk=26[^[:blank:]]*/jdk=26/" | sed "s/jdk=24[^[:blank:]]*/jdk=24/" |
    sed "s/jg=[^[:blank:]]*//g" | sed "s/ispn=[^[:blank:]]*//g" |
    sed -E "s/[0-9]{0,2}:[0-9]{0,2}(:[0-9]{0,})?//g" |
    sed -E "s/[0-9]{2,4}-[0-9]{2}-[0-9]{2}//g" |
    tr -s ' '
