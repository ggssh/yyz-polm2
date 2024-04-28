#!/bin/bash

source defs.sh

# Prepare dirs.
sudo rm -rf $olr_output/* $olr_logs/* &> /dev/null
mkdir $olr_output $olr_logs &> /dev/null

# Build aux projects.
# TODO - ask if we want to build OLR-AR
#echo "Building OLR-AR..."
#cd $olr_ar_home
#mvn package &> $build_ar
#ret=$?
#if [ "$ret" -ne 0 ]
#then
#	cat $build_ar;
#	echo "Building OLR-AR...Failed!";
#	exit 1
#fi
#echo "Building OLR-AR...OK"
#cd - &> /dev/null

echo "Building OLR-DP..."
cd $olr_dp_home
make &> $build_dp
ret=$?
if [ "$ret" -ne 0 ]
then
	cat $build_dp
	echo "Building OLR-DP...Failed!"
	exit 1
fi
cd - &> /dev/null
echo "Building OLR-DP...OK"

# Lauch test
echo "Launching $target..."
$olr_test_home/$target
echo "jvm pid = `cat $jvm_pid`"
echo "Launching $target...Done"

echo "Wait for the jvm to warm-up..."
sleep $warmup
echo "Wait for the jvm to warm-up...Done"

# Lauch dumper
while true; do
  read -p "Start Dumper? " yn
  case $yn in
    [Yy]* ) ./dump-jvm.sh; break;;
    [Nn]* ) break;;
        * ) echo "Please answer yes or no. ";;
  esac
done

# Stop test.
while true; do
  read -p "Stop JVM? " yn
  case $yn in
    [Yy]* ) ./stop-jvm.sh; break;;
    [Nn]* ) break;;
        * ) echo "Please answer yes or no. ";;
  esac
done

# Backup allocs data before restoring jvm.
echo "Backing up allocs data..."
rm -rf $ar_output_bak &> /dev/null
mkdir $ar_output_bak &> /dev/null
cp -r $ar_output/olr-ar* $ar_output_bak &> /dev/null
echo "Backing up allocs data...Done"

# Lauch heap dump extractor
while true; do
  read -p "Extract heap dumps? " yn
  case $yn in
    [Yy]* ) ./dump-heap.sh; break;;
    [Nn]* ) break;;
        * ) echo "Please answer yes or no. ";;
  esac
done
