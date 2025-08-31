#!/usr/bin/env bash
set -e
echo "=== Building Spring Boot + Vaadin app on Render ==="
./mvnw clean package -DskipTests -Pproduction
