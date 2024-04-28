includes = -I$(java_home)/include -I$(java_home)/include/linux

all: OLR-DumperAgent.so Dumper

OLR-DumperAgent.so: DumperAgent.o
	gcc -o OLR-DumperAgent.so DumperAgent.o -shared -fPIC

DumperAgent.o: DumperAgent.cpp
	gcc -c -g $(includes) -fPIC -o DumperAgent.o DumperAgent.cpp

Dumper: Dumper.o
	gcc -o Dumper Dumper.o

Dumper.o: Dumper.cpp
	gcc -c -g Dumper.cpp

clean:
	rm OLR-DumperAgent.so DumperAgent.o Dumper Dumper.o
