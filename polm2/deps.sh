#!/bin/bash

export git_home=~/git

export olr_home=$git_home/ObjectLifetimeRecorder
export olr_ar_home=$git_home/allocation-instrumenter
export olr_dp_home=$olr_home/OLR-Dumper
export olr_test_home=$olr_home/OLR-Test

export criu_home=$git_home/criu-master
export crit=$criu_home/crit/crit
export criu=$criu_home/criu/criu

#export java_home=/usr/lib/jvm/java-8-oracle
#export java_home=/usr/local/jvm/openjdk-1.8.0-internal-g1
export java_home=/usr/local/jvm/openjdk-1.8.0-internal
#export java_home=/usr/local/jvm/openjdk-1.8.0-internal-debug

export java=$java_home/bin/java
