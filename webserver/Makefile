REGISTRY?=persundecern
IMAGE?=webserver-tdaq
TEMP_DIR:=$(shell mktemp -d)
ARCH?=amd64

BASEIMAGE=luxas/node-$(ARCH):latest

IMG=$(REGISTRY)/$(IMAGE)

all: build

build:
	cp Dockerfile *.js $(TEMP_DIR)
	cd $(TEMP_DIR) && sed -i "s|BASEIMAGE|$(BASEIMAGE)|g" Dockerfile
	sudo docker build -t ${IMG} $(TEMP_DIR)
	sudo docker push ${IMG}