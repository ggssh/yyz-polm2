#!/bin/bash

source defs.sh

jvm_pid=`cat $jvm_pid`

echo "Killing $jvm_pid..."
kill $jvm_pid

while [[ $(awk -v pid=$jvm_pid '$1==pid {print $0}' <(top -n 1 -b)) ]]; do sleep 1; done

echo "Killing $jvm_pid...Done"
