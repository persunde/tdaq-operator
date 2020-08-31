REGISTRY?=persundecern
IMAGE?=webclient-tdaq
TEMP_DIR:=$(shell mktemp -d)
ML_PLATFORMS=linux/amd64,linux/arm,linux/arm64

VERSION?=v0.0.1
BASEIMAGE=python-3.7-slim

IMG=$(REGISTRY)/$(IMAGE):$(VERSION)

build:
	cp Dockerfile *.js $(TEMP_DIR)
	cd $(TEMP_DIR) && sed -i "s|BASEIMAGE|$(BASEIMAGE)|g" Dockerfile
	docker build -t ${IMG} $(TEMP_DIR)
	docker push ${IMG}