REGISTRY?=persundecern
IMAGE?=webclient-tdaq
TEMP_DIR:=$(shell mktemp -d)

BASEIMAGE=python:3.7-slim

IMG=$(REGISTRY)/$(IMAGE)

all: build

build:
	cp Dockerfile *.py $(TEMP_DIR)
	cd $(TEMP_DIR) && sed -i "s|BASEIMAGE|$(BASEIMAGE)|g" Dockerfile
	sudo docker build -t ${IMG} $(TEMP_DIR)
	sudo docker push ${IMG}