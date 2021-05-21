#!/bin/bash

./gradlew build
docker build -t yti-datamodel-api .
