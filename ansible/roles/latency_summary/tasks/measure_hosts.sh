#!/bin/bash

set -e

for address in "$@"
do
    ping -q -c 30 -i 0.5 -s 128 "$address" 2>&1 | awk -v a="$address" 'END{ print a (/^rtt/? " -> "$2 $3 $4" ms":"FAIL") }'
done
