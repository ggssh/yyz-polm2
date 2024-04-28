# polm2                                                                         
Automatic Profiling for Object Lifetime-Aware Memory Management for HotSpot Big Data Applications

To test POLM2 you have to:

1. build OpenJDK source with support for POLM2
2. build the recorder, dumper, analyzer, and dumper
3. review both deps.sh and deps.sh (and adapt to your environment)
4. prepare run-recorder.sh to launch you target application
5. run-analyzer.sh
6. run target application using 'instrumenter' as a JVM agent and give as input to the agent the output of the analyzer.
