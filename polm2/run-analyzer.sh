#!/bin/bash

source defs.sh

heap_size=14g
young_size=6g

olr_home=$git_home/ObjectLifetimeRecorder
olr_oga_home=$olr_home/OLR-ObjectGraphAnalyzer
olr_output=$olr_home/output
olr_logs=$olr_home/logs

jvm_log=$olr_logs/jvm-analyzer.log
oga_log=$olr_logs/oga.log
build_oga=$olr_logs/build-oga.log

java=$java_home/bin/java
classpath="$olr_oga_home/dist/OLR-ObjectGraphAnalyzer.jar"
classpath="$classpath:$olr_oga_home/libs/guava.jar"
classpath="$classpath:$olr_oga_home/libs/hprof-parser-1.0-SNAPSHOT.jar"

jvm_opts="$jvm_opts -Xms$heap_size"
jvm_opts="$jvm_opts -Xmx$heap_size"
jvm_opts="$jvm_opts -Xmn$young_size"
jvm_opts="$jvm_opts -XX:+PrintGCDetails" 
jvm_opts="$jvm_opts -Xloggc:$jvm_log"
jvm_opts="$jvm_opts -XX:-UsePerfData"

echo "Building OLR-OGA..."
cd $olr_oga_home 
ant clean &> /dev/null; ant &> $build_oga
ret=$?
if [ "$ret" -ne 0 ]
then
	cat $build_oga;
	echo "Building OLR-OGA...Failed!";
	exit 1
fi
echo "Building OLR-OGA...OK"
cd - &> /dev/null

hprofs=`ls -v1 $olr_output/dump-*/*.hprof`

echo "Launching analyzer..."
$java $jvm_opts -cp $classpath olr.ga.ObjectGraphAnalyzer $ar_output_bak $hprofs &> $oga_log
echo "Launching analyzer...Done"
