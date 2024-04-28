#!/bin/bash

source defs.sh

# for dump dir
for dir in $dp_output/dump-*
do
	echo "Processing $dir..."

	# Tricking criu into restoring processes whose files are no longer the same.
	sudo $crit decode -i $dir/reg-files.img -o $dir/reg-files.txt
	sudo sed -i -e 's/, \"size\": [0-9]*'//g  $dir/reg-files.txt
	sudo mv $dir/reg-files.img $dir/reg-files.img.bak
	sudo $crit encode -i $dir/reg-files.txt -o $dir/reg-files.img

	echo "Restoring jvm..."
	sudo $criu restore --restore-detached -vvvv -o $dir/restore.log --images-dir $dir
	ret=$?
	if [ "$ret" -ne 0 ]
	then
		sudo cat $dir/restore.log
		echo "Restoring jvm...Failed"
		exit 1
	fi
	echo "Restoring jvm...Done"

	echo "Dumping heap..."
	# Give permissions to write dump to dir
	 sudo chmod o+w $dir
	jmap -J-d64 -dump:format=b,file=$dir/heap-dump.hprof `cat $jvm_pid` >> $dp_log/jmap.log
	ret=$?
	if [ "$ret" -ne 0 ]
	then
		cat $dp_log/jmap.log
		echo "Dumping heap...Failed"
        	exit 1
	fi
	echo "Dumping heap...Done"

	./stop-jvm.sh
	echo "Processing $dir...Done"
done
