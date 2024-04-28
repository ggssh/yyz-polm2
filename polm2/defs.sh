#!/bin/bash
source ./deps.sh

#target=sat.sh
#target=test2.sh
#target=cassandra.sh
target=graphchi.sh
max_dumps=0
warmup=60

# OLR defs
olr_output=$olr_home/output
olr_logs=$olr_home/logs

# Dumper defs
dp=$olr_dp_home/Dumper
build_dp=$olr_logs/build-dp.log
dp_log=$olr_logs
dp_output=$olr_output
dp_opts="$criu $max_dumps $dp_output $dp_log"

# Dumper Agent defs
export dpa=$olr_dp_home/OLR-DumperAgent.so
export dpa_opts=$olr_logs/dpa.log

# Allocation Recorder Agent defs
export ar=$olr_ar_home/target/java-allocation-instrumenter-3.0-SNAPSHOT.jar
ar_output=$olr_home/output
ar_output_bak=$olr_home/output-bak
export ar_opts=$olr_output
build_ar=$olr_logs/build-ar.log

# JVM defs
export jvm_pid=$olr_logs/jvm.pid
