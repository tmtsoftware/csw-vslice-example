#!/bin/sh
#
# Creates a single install directory from all the csw stage directories.

dir=../install

hash sbt 2>/dev/null || { echo >&2 "Please install sbt first.  Aborting."; exit 1; }

stage=target/universal/stage

for i in bin lib conf doc ; do
    test -d $dir/$i || mkdir -p $dir/$i
done

sbt stage

for i in bin lib ; do
    for j in */target/universal/stage/$i/* ; do
        cp -f $j $dir/$i
    done
done

# XXX FIXME: Get the real alarms.conf file from somewhere
cp vslice/src/test/resources/test-alarms.conf $dir/conf/alarms.conf

rm -f $dir/bin/*.log.* $dir/bin/*.bat
