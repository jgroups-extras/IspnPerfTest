#!/bin/bash

# this causes JMH to use vthreads rather than platform threads to execute benchmarks
USE_VTHREADS="-Djmh.executor=VIRTUAL"
PROF=-prof async:libPath=/Users/bela/async-profiler/lib/libasyncProfiler.dylib\;dir=results\;output=flamegraph

CFG="config=tri:jgroups-tcp.xml"
READ_PERCENTAGE="read_percentage=0.8"

# NUmber of warmup iterations
WARMUP_ITERATIONS=0

# Each warmup iteration takes N secs
WARMUP_ITERATION_TIME=0s

# The number of iterations
ITERATIONS=1

# Each iteration takes N secs
ITERATION_TIME=10s

# The number of threads used to run the benchmark method(s) - synchronized unless option '-si' is used
THREADS=1

java  -jar target/benchmarks.jar \
      -jvmArgs=$USE_VTHREADS \
      -wi $WARMUP_ITERATIONS -w $WARMUP_ITERATION_TIME \
      -i $ITERATIONS  -r $ITERATION_TIME \
      -t $THREADS \
      $PROF test \
      -p $READ_PERCENTAGE -p $CFG $*