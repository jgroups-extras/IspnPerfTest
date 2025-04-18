#!/bin/bash

usage() {
  java -jar target/benchmarks.jar -h
}

# this causes JMH to use vthreads rather than platform threads to execute benchmarks
USE_VTHREADS="-Djmh.executor=VIRTUAL"

## Uses async-profiler to profile data into 'results' dir. Comment if profiling is not required
PROF='async:libPath=/Users/bela/async-profiler/lib/libasyncProfiler.dylib;dir=results;output=flamegraph;direction=forward'

CFG="config=tri:jgroups-tcp.xml"
READ_PERCENTAGE="read_percentage=0.8"

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

echo "warmup iterations: $WARMUP_ITERATIONS wi time: $WARMUP_ITERATION_TIME iterations $ITERATIONS time: $ITERATION_TIME"
echo "args: $* rest: $rest"


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
