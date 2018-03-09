# JDWPFactGen

Generate dynamic facts over JDWP for use with the Doop framework, in the place of heap snapshots.

## Connecting to a Java VM
The java application should be run as:
```
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 Class
```
Then, you should run this application and put the correct host and port (in this case, 5005) in the main method.

## Connecting to an Android application (not tested)
Run `adb jdwp` to list the processes that host a JDWP transport. Then, once you have found the pid of your app, run e.g. `adb forward tcp:8700 jdwp:100` in the case that the pid is 100.

Then you should run this application with the correct host and port (in this case, localhost and 8700) in the main method.
