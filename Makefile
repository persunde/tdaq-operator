all: install docker

install:
	mvn install
docker:
	sudo mvn dockerfile:build && sudo docker push persundecern/tdaq-operator