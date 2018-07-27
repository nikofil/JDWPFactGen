all:
	mkdir -p out
	javac -cp ${JAVA_HOME}/lib/tools.jar -d out src/*.java
