#!/usr/bin/env bash
set -e

# Forcer Render à utiliser Java et Maven (pas Node)
echo "=== Building Spring Boot + Vaadin app on Render ==="
chmod +x ./mvnw

# Nettoyer et builder sans tests (plus rapide pour Render)
./mvnw clean package -DskipTests -Pproduction
