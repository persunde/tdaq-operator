REGISTRY?=persundecern
IMAGE?=webserver-tdaq
TEMP_DIR:=$(shell mktemp -d)
ARCH?=amd64
ALL_ARCH=amd64 arm arm64
ML_PLATFORMS=linux/amd64,linux/arm,linux/arm64

VERSION?=v0.0.1
BASEIMAGE=luxas/node-$(ARCH):latest

IMG=$(REGISTRY)/$(IMAGE)-$(ARCH):$(VERSION)

build:
	cp Dockerfile *.js $(TEMP_DIR)
	cd $(TEMP_DIR) && sed -i "s|BASEIMAGE|$(BASEIMAGE)|g" Dockerfile
	docker build -t ${IMG} $(TEMP_DIR)
	docker push ${IMG}