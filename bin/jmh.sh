#!/bin/bash

# this causes JMH to use vthreads rather than platform threads to execute benchmarks
USE_VTHREADS="-Djmh.executor=VIRTUAL"

ARCH=`uname -m`

if [[ $ARCH == "arm"* ]] ; then
  EXT="dylib"
else
  EXT="so"
fi

## Uses async-profiler to profile data into 'results' dir. Comment if profiling is not required
## Change the location of async-profiler!
PROF="async:libPath="
PROF="$PROF$HOME"
PROF="$PROF/async-profiler/lib/libasyncProfiler.$EXT;dir=results;output=flamegraph;direction=forward"


CFG="config=tri:jgroups-tcp.xml"
READ_PERCENTAGE="read_percentage=1.0"

# NUmber of warmup iterations
WARMUP_ITERATIONS='0'

# Each warmup iteration takes N secs
WARMUP_ITERATION_TIME='0s'

# The number of iterations
ITERATIONS='1'

# Each iteration takes N secs
ITERATION_TIME='60s'

# The number of threads used to run the benchmark method(s) - synchronized unless option '-si' is used
THREADS='1'

rest=""

## We need to parse arguments and pass them to JMH, because JMH accepts only one instance of each option:
## '-r 10s -r 60s' is not accepted by JMH!

while [ "$1" != "" ]; do
    case $1 in
        -wi )                   shift
                                WARMUP_ITERATIONS="$1"
                                ;;
        -w )                    shift
                                WARMUP_ITERATION_TIME="$1"
                                ;;
        -i )                    shift
                                ITERATIONS="$1"
                                ;;
        -wi )                   shift
                                WARMUP_ITERATIONS="$1"
                                ;;
        -t )                    shift
                                THREADS="$1"
                                ;;
        -r )                    shift
                                ITERATION_TIME="$1"
                                ;;
        * )                     rest="$rest $1"
                                ;;
    esac
    shift
done

java  -jar target/benchmarks.jar \
      -jvmArgs=$USE_VTHREADS \
      -wi $WARMUP_ITERATIONS \
      -w $WARMUP_ITERATION_TIME \
      -i $ITERATIONS  \
      -r $ITERATION_TIME \
      -t $THREADS \
      -p \"$READ_PERCENTAGE\" \
      -p \"$CFG\" \
      -prof $PROF \
      $rest
